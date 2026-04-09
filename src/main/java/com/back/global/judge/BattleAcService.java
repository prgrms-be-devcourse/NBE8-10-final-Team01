package com.back.global.judge;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;
import com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus;
import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.battle.battleroom.repository.BattleRoomRepository;
import com.back.domain.battle.result.service.BattleResultService;
import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.repository.MemberRepository;
import com.back.global.websocket.BattleTimerStore;
import com.back.global.websocket.pubsub.WebSocketMessagePublisher;

import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class BattleAcService {

    private final BattleParticipantRepository battleParticipantRepository;
    private final BattleRoomRepository battleRoomRepository;
    private final MemberRepository memberRepository;
    private final BattleResultService battleResultService;
    private final BattleTimerStore battleTimerStore;
    private final WebSocketMessagePublisher publisher;

    @Transactional
    public void handleAc(Long roomId, Long memberId) {
        BattleRoom room = battleRoomRepository
                .findById(roomId)
                .orElseThrow(() -> new IllegalStateException("BattleRoom not found: " + roomId));
        Member member = memberRepository
                .findById(memberId)
                .orElseThrow(() -> new IllegalStateException("Member not found: " + memberId));

        BattleParticipant participant = battleParticipantRepository
                .findByBattleRoomAndMember(room, member)
                .orElseThrow(() -> new IllegalStateException("Participant not found"));

        participant.complete(LocalDateTime.now());
        battleParticipantRepository.save(participant);

        List<BattleParticipant> allParticipants = battleParticipantRepository.findByBattleRoom(room);
        long completedCount = allParticipants.stream()
                .filter(p -> p.getStatus() == BattleParticipantStatus.SOLVED)
                .count();
        boolean allFinished = allParticipants.stream().allMatch(p -> p.getStatus() == BattleParticipantStatus.SOLVED);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publisher.publish(
                        "/topic/room/" + roomId,
                        Map.of(
                                "type",
                                "PARTICIPANT_STATUS_CHANGED",
                                "userId",
                                memberId,
                                "status",
                                BattleParticipantStatus.SOLVED.name()));
                publisher.publish(
                        "/topic/room/" + roomId,
                        Map.of("type", "PARTICIPANT_DONE", "userId", memberId, "rank", completedCount));
            }
        });

        if (allFinished) {
            battleTimerStore.cancel(roomId);
            try {
                battleResultService.settle(roomId);
            } catch (OptimisticLockException e) {
                log.info("settle 낙관적 락 충돌 - 이미 정산 완료됨 roomId={}", roomId);
            }
        }
    }
}
