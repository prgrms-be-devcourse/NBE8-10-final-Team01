package com.back.domain.battle.result.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;
import com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus;
import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.battle.battleroom.entity.BattleRoomStatus;
import com.back.domain.battle.battleroom.repository.BattleRoomRepository;
import com.back.domain.battle.result.dto.ActiveRoomResponse;
import com.back.domain.battle.result.dto.BattleResultResponse;
import com.back.domain.battle.result.dto.BattleResultResponse.ParticipantResult;
import com.back.domain.battle.result.dto.MyBattleResultsResponse;
import com.back.domain.battle.result.dto.RoomListResponse;
import com.back.domain.battle.result.dto.UncheckedResultResponse;
import com.back.domain.problem.submission.entity.Submission;
import com.back.domain.problem.submission.entity.SubmissionResult;
import com.back.domain.problem.submission.repository.SubmissionRepository;
import com.back.domain.rating.profile.service.RatingProfileService;
import com.back.global.exception.ServiceException;
import com.back.global.websocket.BattleCodeStore;
import com.back.global.websocket.pubsub.WebSocketMessagePublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class BattleResultService {

    // 오답 패널티: WA 1회당 20초
    private static final long WA_PENALTY_SECONDS = 20L;

    private final BattleRoomRepository battleRoomRepository;
    private final BattleParticipantRepository battleParticipantRepository;
    private final SubmissionRepository submissionRepository;
    private final RatingProfileService ratingProfileService;
    private final WebSocketMessagePublisher publisher;
    private final BattleCodeStore battleCodeStore;

    @Transactional
    public void settle(Long roomId) {
        log.info("settle called roomId={}", roomId);

        // 1. BattleRoom 조회 + 중복 정산 방지
        BattleRoom room =
                battleRoomRepository.findById(roomId).orElseThrow(() -> new IllegalArgumentException("존재하지 않는 방입니다."));

        // 중복 정산 방지
        if (room.getStatus() == BattleRoomStatus.FINISHED) {
            return;
        }

        // 2. 모든 참여자 조회
        List<BattleParticipant> participants = battleParticipantRepository.findByBattleRoom(room);

        // 2-1. 시간 종료 시점까지 아직 PLAYING인 참여자를 TIMEOUT으로 명시적 전환
        //      ABANDONED(네트워크 이탈)와 구분: TIMEOUT은 끝까지 도전했으나 시간 초과
        participants.stream()
                .filter(p -> p.getStatus() == BattleParticipantStatus.PLAYING)
                .forEach(BattleParticipant::timeout);

        // 3. 각 참여자 score 미리 계산 (DB 쿼리를 정렬 전에 한 번씩만 실행)
        //    Comparator 안에서 calcScore() 호출 시 O(N log N) 횟수만큼 DB 조회가 발생하는 문제 방지
        Map<Long, Long> scoreMap =
                participants.stream().collect(Collectors.toMap(BattleParticipant::getId, p -> calcScore(p, room)));

        // 4. score 기준 정렬 (in-memory, DB 쿼리 없음)
        //    AC 참여자: score = 소요시간(초) + WA 패널티(회 * 20초) → 낮을수록 좋음
        //    미통과 참여자: score = Long.MAX_VALUE → 항상 뒤로 밀림
        List<BattleParticipant> sorted = participants.stream()
                .sorted(Comparator.comparingLong((BattleParticipant p) -> scoreMap.get(p.getId()))
                        .thenComparing(p -> p.getFinishTime() != null ? p.getFinishTime() : LocalDateTime.MAX))
                .toList();

        List<RatingProfileService.BattlePlacement> placements = new ArrayList<>(sorted.size());
        List<BattleParticipant> acParticipants = new ArrayList<>();
        Map<Long, Integer> rankByMemberId = new HashMap<>(sorted.size());

        // 4. 등수 부여 + Elo 정산 입력 구성
        int rank = 1;
        for (BattleParticipant participant : sorted) {
            boolean isAC = participant.getStatus() == BattleParticipantStatus.SOLVED;
            boolean isDisconnected = participant.getStatus() == BattleParticipantStatus.ABANDONED
                    || participant.getStatus() == BattleParticipantStatus.QUIT;
            rankByMemberId.put(participant.getMember().getId(), rank);

            if (isAC) {
                acParticipants.add(participant);
            }
            placements.add(new RatingProfileService.BattlePlacement(participant.getMember(), rank, isDisconnected));

            rank++;
        }

        // 배틀 SR 정산(Elo 기반 + 양학 감쇠 + 언더독 보너스 + 포기 패널티).
        Map<Long, Integer> deltaByMemberId = ratingProfileService.applyBattlePlacements(
                placements, room.getProblem().getDifficultyRating());
        if (deltaByMemberId == null) {
            deltaByMemberId = Map.of();
        }
        for (BattleParticipant participant : sorted) {
            int finalRank = rankByMemberId.getOrDefault(participant.getMember().getId(), 0);
            int scoreDelta =
                    deltaByMemberId.getOrDefault(participant.getMember().getId(), 0);
            participant.applyResult(finalRank, scoreDelta);
        }

        for (BattleParticipant participant : acParticipants) {
            // 배틀에서 처음으로 푼 문제라면 난이도 기반 first-AC 보너스를 1회 반영한다.
            ratingProfileService.applyBattleFirstSolve(
                    participant.getMember(), room.getProblem(), participant.getFinishTime());
        }

        // 6. 모든 참여자 결과 미확인 상태로 전환 (재접속 시 결과 화면 유도)
        participants.forEach(BattleParticipant::markUnchecked);

        // 7. BattleRoom 종료
        room.finish();

        // 8. WebSocket 브로드캐스트 — 커밋 후 전송으로 프론트가 확정된 DB 상태를 읽도록 보장
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 커밋 후이므로 예외를 전파해도 트랜잭션 롤백이 불가능하고,
                // 클라이언트에게 500이 반환되어 DB는 성공했는데 실패로 인식하는 혼란을 유발함.
                // WebSocket은 실시간 알림 역할이므로 전송 실패가 치명적이지 않아 예외를 삼키고 로그만 남김.

                // 방 채널 브로드캐스트 (방 내 구독자 및 관전자)
                try {
                    for (BattleParticipant p : participants) {
                        if (p.getStatus() != BattleParticipantStatus.TIMEOUT) {
                            continue;
                        }
                        try {
                            publisher.publish(
                                    "/topic/room/" + roomId,
                                    Map.of(
                                            "type", "PARTICIPANT_STATUS_CHANGED",
                                            "userId", p.getMember().getId(),
                                            "status", BattleParticipantStatus.TIMEOUT.name()));
                        } catch (Exception inner) {
                            log.error(
                                    "Failed to publish PARTICIPANT_STATUS_CHANGED(TIMEOUT) WebSocket roomId={}",
                                    roomId,
                                    inner);
                        }
                    }
                    publisher.publish("/topic/room/" + roomId, Map.of("type", "BATTLE_FINISHED"));
                } catch (Exception e) {
                    log.error("BATTLE_FINISHED WebSocket 전송 실패 roomId={}", roomId, e);
                }

                // 개인 채널 알림 — 방을 나간 참여자(AC 후 이탈)도 결과를 즉시 수신 가능
                for (BattleParticipant p : sorted) {
                    try {
                        publisher.publish(
                                "/topic/user/" + p.getMember().getId() + "/battle",
                                Map.of(
                                        "type",
                                        "BATTLE_RESULT",
                                        "roomId",
                                        roomId,
                                        "rank",
                                        p.getFinalRank(),
                                        "scoreDelta",
                                        p.getScoreDelta()));
                    } catch (Exception e) {
                        log.error("개인 알림 전송 실패 memberId={}", p.getMember().getId(), e);
                    }
                }

                // 방 종료 후 관전용 코드 Redis 정리
                try {
                    battleCodeStore.deleteAllByRoom(roomId);
                } catch (Exception e) {
                    log.error("배틀 코드 Redis 정리 실패 roomId={}", roomId, e);
                }
            }
        });
        log.info("settle end roomId={}", roomId);
    }

    @Transactional(readOnly = true)
    public BattleResultResponse getResult(Long roomId) {

        BattleRoom room =
                battleRoomRepository.findById(roomId).orElseThrow(() -> new IllegalArgumentException("존재하지 않는 방입니다."));

        if (room.getStatus() != BattleRoomStatus.FINISHED) {
            throw new IllegalStateException("아직 정산이 완료되지 않은 방입니다. 현재 상태: " + room.getStatus());
        }

        List<BattleParticipant> participants = battleParticipantRepository.findByBattleRoom(room);

        List<ParticipantResult> results = participants.stream()
                .sorted(Comparator.comparingInt(BattleParticipant::getFinalRank))
                .map(p -> ParticipantResult.from(p, findBestSubmission(room, p)))
                .toList();

        return BattleResultResponse.from(room, results);
    }

    @Transactional(readOnly = true)
    public List<RoomListResponse> getRoomList() {

        List<BattleRoom> playingRooms = battleRoomRepository.findByStatus(BattleRoomStatus.PLAYING);

        return playingRooms.stream()
                .map(room -> {
                    int currentPlayers = (int) battleParticipantRepository.countByBattleRoom(room);
                    return RoomListResponse.from(room, currentPlayers);
                })
                .toList();
    }

    /**
     * 참여자의 최고 제출 조회
     * AC 제출 우선, 없으면 passedCount 가장 높은 제출, 제출 없으면 null
     * Judge0 연동 후 여러 번 제출 가능해지면 이 로직으로 자연스럽게 대응 가능
     */
    private Submission findBestSubmission(BattleRoom room, BattleParticipant participant) {
        // AC 제출이 있으면 첫 번째 AC 반환
        Optional<Submission> acSubmission =
                submissionRepository.findFirstByBattleRoomAndMemberAndResultOrderByCreatedAtAsc(
                        room, participant.getMember(), SubmissionResult.AC);

        if (acSubmission.isPresent()) {
            return acSubmission.get();
        }

        // 없으면 passedCount 가장 높은 제출 반환
        return submissionRepository.findByBattleRoomAndMember(room, participant.getMember()).stream()
                .filter(s -> s.getPassedCount() != null)
                .max(Comparator.comparingInt(Submission::getPassedCount))
                .orElse(null);
    }

    /**
     * 순위 산정 기준값 계산
     * AC 참여자: 소요시간(초) + WA 패널티
     * 미통과 참여자: Long.MAX_VALUE
     */
    private long calcScore(BattleParticipant participant, BattleRoom room) {
        if (participant.getStatus() != BattleParticipantStatus.SOLVED) {
            return Long.MAX_VALUE;
        }

        // null 방어: 정상 흐름에서는 발생하지 않지만 데이터 이상 시 정산 전체가 실패하는 것을 방지
        if (room.getStartedAt() == null || participant.getFinishTime() == null) {
            log.warn(
                    "startedAt 또는 finishTime이 null - 미통과로 처리. roomId={}, participantId={}",
                    room.getId(),
                    participant.getId());
            return Long.MAX_VALUE;
        }

        long elapsedSeconds = ChronoUnit.SECONDS.between(room.getStartedAt(), participant.getFinishTime());

        long waPenaltyCount = submissionRepository.countByBattleRoomAndMemberAndResultAndCreatedAtBefore(
                room, participant.getMember(), SubmissionResult.WA, participant.getFinishTime());

        return elapsedSeconds + (waPenaltyCount * WA_PENALTY_SECONDS);
    }

    /**
     * 로그인 유저의 미확인 배틀 결과 조회 (가장 최근 1건).
     * 브라우저를 닫고 재접속한 경우 이 API로 결과 화면으로 유도한다.
     */
    @Transactional(readOnly = true)
    public UncheckedResultResponse getUncheckedResult(Long memberId) {
        return battleParticipantRepository.findUncheckedResultsByMemberId(memberId, PageRequest.of(0, 1)).stream()
                .findFirst()
                .map(UncheckedResultResponse::from)
                .orElse(null);
    }

    /**
     * 결과 확인 처리 — 결과 화면 진입 시 호출.
     */
    @Transactional
    public void checkResult(Long roomId, Long memberId) {
        battleParticipantRepository
                .findByBattleRoomIdAndMemberId(roomId, memberId)
                .ifPresent(BattleParticipant::markChecked);
    }

    @Transactional(readOnly = true)
    public ActiveRoomResponse getActiveRoom(Long memberId) {
        return battleParticipantRepository
                .findActiveParticipantByMemberId(memberId)
                .map(p -> new ActiveRoomResponse(p.getBattleRoom().getId()))
                .orElse(null);
    }

    /**
     * 현재 로그인 사용자의 전적 목록 조회용 서비스 메서드
     *
     * memberId:
     * - 컨트롤러에서 rq.getActor() 로 꺼낸 현재 사용자 id
     *
     * page, size:
     * - 0-based 페이지 기준
     */
    @Transactional(readOnly = true)
    public MyBattleResultsResponse getMyBattleResults(Long memberId, int page, int size) {
        // 로그인 사용자 없이 호출되면 예외
        if (memberId == null) {
            throw new ServiceException("MEMBER_401", "로그인이 필요합니다.");
        }

        // 잘못된 페이지 파라미터 방어
        if (page < 0 || size < 1) {
            throw new ServiceException("MEMBER_400", "page는 0 이상이고 size는 1 이상이어야 합니다.");
        }

        // 종료된(FINISHED) 배틀만 전적으로 조회
        Page<BattleParticipant> participantPage = battleParticipantRepository.findFinishedBattleResultsByMemberId(
                memberId, BattleRoomStatus.FINISHED, PageRequest.of(page, size));

        // 엔티티 페이지를 응답 DTO로 변환
        return MyBattleResultsResponse.from(participantPage);
    }
}
