package com.back.domain.battle.result.service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
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
import com.back.domain.member.member.repository.MemberRepository;
import com.back.domain.problem.submission.entity.Submission;
import com.back.domain.problem.submission.entity.SubmissionResult;
import com.back.domain.problem.submission.repository.SubmissionRepository;
import com.back.global.exception.ServiceException;
import com.back.global.websocket.pubsub.WebSocketMessagePublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class BattleResultService {

    // 점수 정책: 1등 +100, 2등 +70, 3등 +40, 4등 +20, 미통과 +0
    // TODO: 점수 정책 추후 협의 후 조정 가능
    private static final long[] SCORE_TABLE = {100L, 70L, 40L, 20L};

    // 오답 패널티: WA 1회당 20초
    private static final long WA_PENALTY_SECONDS = 20L;

    private final BattleRoomRepository battleRoomRepository;
    private final BattleParticipantRepository battleParticipantRepository;
    private final SubmissionRepository submissionRepository;
    private final MemberRepository memberRepository;
    private final WebSocketMessagePublisher publisher;

    @Transactional
    public void settle(Long roomId) {

        // 1. BattleRoom 조회 + 중복 정산 방지
        BattleRoom room =
                battleRoomRepository.findById(roomId).orElseThrow(() -> new IllegalArgumentException("존재하지 않는 방입니다."));

        // 중복 정산 방지
        if (room.getStatus() == BattleRoomStatus.FINISHED) {
            return;
        }

        // 2. 모든 참여자 조회
        List<BattleParticipant> participants = battleParticipantRepository.findByBattleRoom(room);

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

        // 4. 등수 & 점수 부여
        int rank = 1;
        for (BattleParticipant participant : sorted) {
            boolean isAC = participant.getStatus() == BattleParticipantStatus.EXIT;
            long scoreDelta = isAC ? SCORE_TABLE[Math.min(rank - 1, SCORE_TABLE.length - 1)] : 0L;

            participant.applyResult(rank, scoreDelta);

            // 5. Member.score 갱신
            participant.getMember().applyScore(scoreDelta);

            rank++;
        }

        // 6. BattleRoom 종료
        room.finish();

        // 7. WebSocket 브로드캐스트 — 커밋 후 전송으로 프론트가 확정된 DB 상태를 읽도록 보장
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                // 커밋 후이므로 예외를 전파해도 트랜잭션 롤백이 불가능하고,
                // 클라이언트에게 500이 반환되어 DB는 성공했는데 실패로 인식하는 혼란을 유발함.
                // WebSocket은 실시간 알림 역할이므로 전송 실패가 치명적이지 않아 예외를 삼키고 로그만 남김.
                try {
                    publisher.publish("/topic/room/" + roomId, Map.of("type", "BATTLE_FINISHED"));
                } catch (Exception e) {
                    log.error("BATTLE_FINISHED WebSocket 전송 실패 roomId={}", roomId, e);
                }
            }
        });
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
        if (participant.getStatus() != BattleParticipantStatus.EXIT) {
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
