package com.back.global.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.context.ApplicationEventPublisher;
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

class BattleAcServiceTest {

    private static final Long ROOM_ID = 10L;
    private static final Long MEMBER_ID = 100L;

    private final BattleParticipantRepository battleParticipantRepository = mock(BattleParticipantRepository.class);
    private final BattleRoomRepository battleRoomRepository = mock(BattleRoomRepository.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final WebSocketMessagePublisher publisher = mock(WebSocketMessagePublisher.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);

    private final BattleAcService battleAcService = new BattleAcService(
            battleParticipantRepository, battleRoomRepository, memberRepository, publisher, eventPublisher);

    @Test
    @DisplayName("AC 처리 시 participant를 SOLVED로 저장하고 커밋 후 상태 이벤트와 완료 이벤트를 발행한다")
    void handleAc_marksSolvedAndPublishesAfterCommit() {
        BattleRoom room = mock(BattleRoom.class);
        Member member = mock(Member.class);
        when(member.getId()).thenReturn(MEMBER_ID);

        BattleParticipant participant = BattleParticipant.create(room, member);
        participant.join();

        BattleParticipant solvedOther = mock(BattleParticipant.class);
        when(solvedOther.getStatus()).thenReturn(BattleParticipantStatus.SOLVED);

        when(battleRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(battleParticipantRepository.findByBattleRoomAndMember(room, member))
                .thenReturn(Optional.of(participant));
        when(battleParticipantRepository.findByBattleRoom(room)).thenReturn(List.of(participant, solvedOther));

        LocalDateTime submittedAt = LocalDateTime.now();
        withAfterCommit(() -> battleAcService.handleAc(ROOM_ID, MEMBER_ID, submittedAt));

        assertThat(participant.getStatus()).isEqualTo(BattleParticipantStatus.SOLVED);
        assertThat(participant.getFinishTime()).isEqualTo(submittedAt);
        verify(battleParticipantRepository).save(participant);
        verify(publisher, times(2)).publish(eq("/topic/room/" + ROOM_ID), any());
        verify(publisher)
                .publish(
                        eq("/topic/room/" + ROOM_ID),
                        eq(Map.of(
                                "type",
                                "PARTICIPANT_STATUS_CHANGED",
                                "userId",
                                MEMBER_ID,
                                "status",
                                BattleParticipantStatus.SOLVED.name())));
        verify(publisher)
                .publish(
                        eq("/topic/room/" + ROOM_ID),
                        eq(Map.of("type", "PARTICIPANT_DONE", "userId", MEMBER_ID, "rank", 2L)));
    }

    @Test
    @DisplayName("활성 참가자가 없으면 정산 요청 이벤트를 발행한다")
    void handleAc_noActiveLeft_publishesSettlementRequestedEvent() {
        BattleRoom room = mock(BattleRoom.class);
        Member member = mock(Member.class);

        BattleParticipant participant = BattleParticipant.create(room, member);
        participant.join();

        BattleParticipant quitOther = mock(BattleParticipant.class);
        when(quitOther.getStatus()).thenReturn(BattleParticipantStatus.QUIT);

        when(battleRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(battleParticipantRepository.findByBattleRoomAndMember(room, member))
                .thenReturn(Optional.of(participant));
        when(battleParticipantRepository.findByBattleRoom(room)).thenReturn(List.of(participant, quitOther));

        withAfterCommit(() -> battleAcService.handleAc(ROOM_ID, MEMBER_ID, LocalDateTime.now()));

        verify(eventPublisher).publishEvent(new BattleSettlementRequestedEvent(ROOM_ID));
    }

    @Test
    @DisplayName("ABANDONED 참여자가 남아있으면 정산 요청 이벤트를 발행하지 않는다")
    void handleAc_abandonedParticipantRemains_doesNotPublishSettlementRequestedEvent() {
        BattleRoom room = mock(BattleRoom.class);
        Member member = mock(Member.class);

        BattleParticipant participant = BattleParticipant.create(room, member);
        participant.join();

        BattleParticipant abandonedOther = mock(BattleParticipant.class);
        when(abandonedOther.getStatus()).thenReturn(BattleParticipantStatus.ABANDONED);

        when(battleRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(battleParticipantRepository.findByBattleRoomAndMember(room, member))
                .thenReturn(Optional.of(participant));
        when(battleParticipantRepository.findByBattleRoom(room)).thenReturn(List.of(participant, abandonedOther));

        withAfterCommit(() -> battleAcService.handleAc(ROOM_ID, MEMBER_ID, LocalDateTime.now()));

        verify(eventPublisher, never()).publishEvent(any(BattleSettlementRequestedEvent.class));
    }

    private void withAfterCommit(Runnable action) {
        try (MockedStatic<TransactionSynchronizationManager> tsm =
                mockStatic(TransactionSynchronizationManager.class)) {
            tsm.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                    .thenAnswer(invocation -> {
                        invocation.<TransactionSynchronization>getArgument(0).afterCommit();
                        return null;
                    });
            action.run();
        }
    }
}
