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

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
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

class BattleAcServiceTest {

    private static final Long ROOM_ID = 10L;
    private static final Long MEMBER_ID = 100L;

    private final BattleParticipantRepository battleParticipantRepository = mock(BattleParticipantRepository.class);
    private final BattleRoomRepository battleRoomRepository = mock(BattleRoomRepository.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final BattleResultService battleResultService = mock(BattleResultService.class);
    private final BattleTimerStore battleTimerStore = mock(BattleTimerStore.class);
    private final WebSocketMessagePublisher publisher = mock(WebSocketMessagePublisher.class);

    private final BattleAcService battleAcService = new BattleAcService(
            battleParticipantRepository,
            battleRoomRepository,
            memberRepository,
            battleResultService,
            battleTimerStore,
            publisher);

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

        withAfterCommit(() -> battleAcService.handleAc(ROOM_ID, MEMBER_ID));

        assertThat(participant.getStatus()).isEqualTo(BattleParticipantStatus.SOLVED);
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
    @DisplayName("전원이 SOLVED면 타이머를 취소하고 정산을 호출한다")
    void handleAc_allFinished_cancelsTimerAndSettles() {
        BattleRoom room = mock(BattleRoom.class);
        Member member = mock(Member.class);

        BattleParticipant participant = BattleParticipant.create(room, member);
        participant.join();

        BattleParticipant solvedOther = mock(BattleParticipant.class);
        when(solvedOther.getStatus()).thenReturn(BattleParticipantStatus.SOLVED);

        when(battleRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(battleParticipantRepository.findByBattleRoomAndMember(room, member))
                .thenReturn(Optional.of(participant));
        when(battleParticipantRepository.findByBattleRoom(room)).thenReturn(List.of(participant, solvedOther));

        withAfterCommit(() -> battleAcService.handleAc(ROOM_ID, MEMBER_ID));

        verify(battleTimerStore).cancel(ROOM_ID);
        verify(battleResultService).settle(ROOM_ID);
    }

    @Test
    @DisplayName("미완료 참가자가 남아있으면 정산하지 않는다")
    void handleAc_notAllFinished_doesNotSettle() {
        BattleRoom room = mock(BattleRoom.class);
        Member member = mock(Member.class);

        BattleParticipant participant = BattleParticipant.create(room, member);
        participant.join();

        BattleParticipant playingOther = mock(BattleParticipant.class);
        when(playingOther.getStatus()).thenReturn(BattleParticipantStatus.PLAYING);

        when(battleRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(battleParticipantRepository.findByBattleRoomAndMember(room, member))
                .thenReturn(Optional.of(participant));
        when(battleParticipantRepository.findByBattleRoom(room)).thenReturn(List.of(participant, playingOther));

        withAfterCommit(() -> battleAcService.handleAc(ROOM_ID, MEMBER_ID));

        verify(battleResultService, never()).settle(any());
        verify(battleTimerStore, never()).cancel(any());
    }

    private void withAfterCommit(Runnable action) {
        try (MockedStatic<TransactionSynchronizationManager> tsm =
                mockStatic(TransactionSynchronizationManager.class)) {
            tsm.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                    .thenAnswer(inv -> {
                        inv.<TransactionSynchronization>getArgument(0).afterCommit();
                        return null;
                    });
            action.run();
        }
    }
}
