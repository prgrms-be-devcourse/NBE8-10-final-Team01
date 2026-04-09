package com.back.global.judge;

import java.time.LocalDateTime;
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
import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.battle.battleroom.repository.BattleRoomRepository;
import com.back.domain.battle.result.event.BattleSettlementRequestedEvent;
import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.repository.MemberRepository;
import com.back.global.websocket.pubsub.WebSocketMessagePublisher;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BattleAcService {

    private final BattleParticipantRepository battleParticipantRepository;
    private final BattleRoomRepository battleRoomRepository;
    private final MemberRepository memberRepository;
    private final WebSocketMessagePublisher publisher;
    private final ApplicationEventPublisher eventPublisher;

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
        boolean noActiveLeft = allParticipants.stream()
                .noneMatch(p -> p.getStatus() == BattleParticipantStatus.PLAYING
                        || p.getStatus() == BattleParticipantStatus.ABANDONED);

        if (noActiveLeft) {
            eventPublisher.publishEvent(new BattleSettlementRequestedEvent(roomId));
        }

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
    }
}
