package com.back.domain.battle.battleroom.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;
import com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus;
import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.domain.battle.battleroom.dto.BattleRoomStateResponse;
import com.back.domain.battle.battleroom.dto.CreateRoomRequest;
import com.back.domain.battle.battleroom.dto.CreateRoomResponse;
import com.back.domain.battle.battleroom.dto.JoinRoomResponse;
import com.back.domain.battle.battleroom.dto.OngoingRoomResponse;
import com.back.domain.battle.battleroom.dto.RoomResponse;
import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.battle.battleroom.entity.BattleRoomStatus;
import com.back.domain.battle.battleroom.repository.BattleRoomRepository;
import com.back.domain.battle.result.event.BattleSettlementRequestedEvent;
import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.repository.MemberRepository;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.problem.problem.repository.ProblemRepository;
import com.back.global.exception.ServiceException;
import com.back.global.websocket.BattleCodeStore;
import com.back.global.websocket.BattleReconnectStore;
import com.back.global.websocket.BattleTimerStore;
import com.back.global.websocket.pubsub.WebSocketMessagePublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class BattleRoomService {

    private final BattleRoomRepository battleRoomRepository;
    private final BattleParticipantRepository battleParticipantRepository;
    private final ProblemRepository problemRepository;
    private final MemberRepository memberRepository;
    private final WebSocketMessagePublisher publisher;
    private final BattleCodeStore battleCodeStore;
    private final BattleReconnectStore reconnectStore;
    private final BattleTimerStore battleTimerStore;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public CreateRoomResponse createRoom(CreateRoomRequest request) {

        // 1. problemId=1 로 문제 조회, 없으면 예외
        Problem problem = problemRepository
                .findById(request.problemId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 문제입니다."));

        // 2. BattleRoom 생성 후 저장
        BattleRoom room = BattleRoom.create(problem, request.maxPlayers());
        battleRoomRepository.save(room);

        // 3. 참여자 목록 조회
        List<Member> members = memberRepository.findAllById(request.participantIds());
        if (members.size() != request.participantIds().size()) {
            throw new IllegalArgumentException("존재하지 않는 참여자가 포함되어 있습니다.");
        }

        // 4. BattleParticipant 4개 생성 후 저장 (4명으로 고정이니까)
        // TODO: battleParticipantRepository::save를 효율적으로 바꿔야할지도 모름
        members.stream()
                .map(member -> BattleParticipant.create(room, member))
                .forEach(battleParticipantRepository::save);

        return CreateRoomResponse.from(room);
    }

    @Transactional
    public JoinRoomResponse joinRoom(Long roomId, Long memberId) {
        boolean publishPlaying = false;
        String battleTimerEnd = null;

        // 1. BattleRoom 조회 (비관적 락으로 동시 joinRoom 직렬화)
        BattleRoom room = battleRoomRepository
                .findByIdWithLock(roomId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 방입니다."));

        // WAITING(최초 입장) 또는 PLAYING(재입장) 상태만 허용
        if (room.getStatus() != BattleRoomStatus.WAITING && room.getStatus() != BattleRoomStatus.PLAYING) {
            throw new IllegalStateException("입장할 수 없는 상태의 방입니다. 현재 상태: " + room.getStatus());
        }

        Member member =
                memberRepository.findById(memberId).orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다."));

        BattleParticipant participant = battleParticipantRepository
                .findByBattleRoomAndMember(room, member)
                .orElseThrow(() -> new IllegalArgumentException("해당 방의 참여자가 아닙니다."));

        if (participant.getStatus() == BattleParticipantStatus.READY
                || participant.getStatus() == BattleParticipantStatus.ABANDONED) {
            if (participant.getStatus() == BattleParticipantStatus.ABANDONED) {
                reconnectStore.cancelGracePeriod(memberId);
            }
            participant.join();
            battleParticipantRepository.save(participant);
            publishPlaying = true;
        } else {
            throw new IllegalStateException("입장할 수 없는 참여자 상태입니다. 현재 상태: " + participant.getStatus());
        }

        if (room.getStatus() == BattleRoomStatus.WAITING) {
            List<BattleParticipant> allParticipants = battleParticipantRepository.findByBattleRoom(room);
            boolean allPlaying =
                    allParticipants.stream().allMatch(p -> p.getStatus() == BattleParticipantStatus.PLAYING);

            if (allPlaying) {
                room.startBattle(Duration.ofMinutes(30));
                battleRoomRepository.save(room);
                battleTimerEnd = room.getTimerEnd().toString();
                String timerEnd = battleTimerEnd;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        try {
                            publisher.publish(
                                    "/topic/room/" + roomId, Map.of("type", "BATTLE_STARTED", "timerEnd", timerEnd));
                        } catch (Exception e) {
                            log.error("BATTLE_STARTED WebSocket 전송 실패 roomId={}", roomId, e);
                        }
                        try {
                            battleTimerStore.schedule(roomId);
                        } catch (Exception e) {
                            log.error("배틀 타이머 예약 실패 roomId={} - 스케줄러 안전망이 복구 예정", roomId, e);
                        }
                    }
                });
            }
        }

        if (publishPlaying) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        publisher.publish(
                                "/topic/room/" + roomId,
                                Map.of(
                                        "type",
                                        "PARTICIPANT_STATUS_CHANGED",
                                        "userId",
                                        memberId,
                                        "status",
                                        BattleParticipantStatus.PLAYING.name()));
                    } catch (Exception e) {
                        log.error(
                                "Failed to publish PARTICIPANT_STATUS_CHANGED(PLAYING) WebSocket roomId={}", roomId, e);
                    }
                }
            });
        }

        List<BattleParticipant> allParticipantsForResponse = battleParticipantRepository.findByBattleRoom(room);
        return JoinRoomResponse.from(room, allParticipantsForResponse);
    }

    @Transactional(readOnly = true)
    public RoomResponse getRoomInfo(Long roomId) {

        BattleRoom room = battleRoomRepository
                .findByIdWithProblem(roomId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 방입니다."));

        List<BattleParticipant> participants = battleParticipantRepository.findByBattleRoom(room);
        return RoomResponse.from(room, participants);
    }

    @Transactional
    public void exitRoom(Long roomId, Long memberId) {

        BattleRoom room =
                battleRoomRepository.findById(roomId).orElseThrow(() -> new ServiceException("404-1", "존재하지 않는 방입니다."));

        if (room.getStatus() != BattleRoomStatus.PLAYING) {
            throw new ServiceException("400-1", "진행 중인 방이 아닙니다.");
        }

        Member member =
                memberRepository.findById(memberId).orElseThrow(() -> new ServiceException("404-1", "존재하지 않는 회원입니다."));

        BattleParticipant participant = battleParticipantRepository
                .findByBattleRoomAndMember(room, member)
                .orElseThrow(() -> new ServiceException("403-1", "해당 방의 참여자가 아닙니다."));

        if (participant.getStatus() == BattleParticipantStatus.ABANDONED) {
            reconnectStore.cancelGracePeriod(memberId);
        } else if (participant.getStatus() != BattleParticipantStatus.PLAYING) {
            throw new ServiceException("400-1", "퇴장할 수 없는 상태입니다. 현재 상태: " + participant.getStatus());
        }

        participant.quit();
        battleParticipantRepository.save(participant);

        List<BattleParticipant> all = battleParticipantRepository.findByBattleRoom(room);
        boolean noActiveLeft = all.stream()
                .noneMatch(p -> p.getStatus() == BattleParticipantStatus.PLAYING
                        || p.getStatus() == BattleParticipantStatus.ABANDONED);

        if (noActiveLeft) {
            log.info("publish BattleSettlementRequestedEvent roomId={}, noActiveLeft={}", roomId, noActiveLeft);
            eventPublisher.publishEvent(new BattleSettlementRequestedEvent(roomId));
        }

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    publisher.publish(
                            "/topic/room/" + roomId,
                            Map.of(
                                    "type",
                                    "PARTICIPANT_STATUS_CHANGED",
                                    "userId",
                                    memberId,
                                    "status",
                                    BattleParticipantStatus.QUIT.name()));
                } catch (Exception e) {
                    log.error("Failed to publish PARTICIPANT_STATUS_CHANGED(QUIT) WebSocket roomId={}", roomId, e);
                }
            }
        });
    }

    @Transactional(readOnly = true)
    public OngoingRoomResponse getOngoingRoom(Long memberId) {
        return battleParticipantRepository
                .findAbandonedParticipantByMemberId(
                        memberId, BattleParticipantStatus.ABANDONED, BattleRoomStatus.PLAYING)
                .map(p -> new OngoingRoomResponse(p.getBattleRoom().getId()))
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public BattleRoomStateResponse getRoomState(Long roomId, Long memberId) {

        BattleRoom room = battleRoomRepository
                .findByIdWithProblem(roomId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 방입니다."));

        if (!battleParticipantRepository.existsByBattleRoomIdAndMemberId(roomId, memberId)) {
            throw new ServiceException("403-1", "해당 방의 참여자가 아닙니다.");
        }

        List<BattleParticipant> participants = battleParticipantRepository.findByBattleRoom(room);
        String myCode = battleCodeStore.get(roomId, memberId);
        return BattleRoomStateResponse.from(room, participants, myCode);
    }
}
