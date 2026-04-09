package com.back.domain.battle.battleroom.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
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
import com.back.domain.battle.battleroom.entity.BattleRoomStatus;
import com.back.domain.battle.battleroom.repository.BattleRoomRepository;
import com.back.domain.battle.result.service.BattleResultService;
import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.repository.MemberRepository;
import com.back.domain.problem.problem.repository.ProblemRepository;
import com.back.global.exception.ServiceException;
import com.back.global.websocket.BattleCodeStore;
import com.back.global.websocket.BattleReconnectStore;
import com.back.global.websocket.BattleTimerStore;
import com.back.global.websocket.pubsub.WebSocketMessagePublisher;

class BattleRoomServiceExitRoomTest {

    private final BattleRoomRepository battleRoomRepository = mock(BattleRoomRepository.class);
    private final BattleParticipantRepository battleParticipantRepository = mock(BattleParticipantRepository.class);
    private final ProblemRepository problemRepository = mock(ProblemRepository.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final WebSocketMessagePublisher publisher = mock(WebSocketMessagePublisher.class);
    private final BattleCodeStore battleCodeStore = mock(BattleCodeStore.class);
    private final BattleReconnectStore reconnectStore = mock(BattleReconnectStore.class);
    private final BattleTimerStore battleTimerStore = mock(BattleTimerStore.class);
    private final BattleResultService battleResultService = mock(BattleResultService.class);

    private final BattleRoomService sut = new BattleRoomService(
            battleRoomRepository,
            battleParticipantRepository,
            problemRepository,
            memberRepository,
            publisher,
            battleCodeStore,
            reconnectStore,
            battleTimerStore,
            battleResultService);

    private static final Long ROOM_ID = 1L;
    private static final Long MEMBER_ID = 10L;

    @Test
    @DisplayName("PLAYING 참여자가 퇴장하면 QUIT 상태 이벤트를 브로드캐스트한다")
    void exitRoom_playingParticipant_broadcastsQuitStatus() {
        BattleRoom room = playingRoom();
        Member member = mockMember();
        BattleParticipant participant = playingParticipant(room, member);

        givenRoomMemberParticipant(room, member, participant, List.of(participant));

        withAfterCommit(() -> sut.exitRoom(ROOM_ID, MEMBER_ID));

        assertThat(participant.getStatus()).isEqualTo(BattleParticipantStatus.QUIT);
        verify(publisher)
                .publish(
                        eq("/topic/room/" + ROOM_ID),
                        eq(Map.of(
                                "type",
                                "PARTICIPANT_STATUS_CHANGED",
                                "userId",
                                MEMBER_ID,
                                "status",
                                BattleParticipantStatus.QUIT.name())));
        verify(reconnectStore, never()).cancelGracePeriod(any());
    }

    @Test
    @DisplayName("ABANDONED 참여자가 퇴장하면 grace period를 취소하고 QUIT 상태 이벤트를 브로드캐스트한다")
    void exitRoom_abandonedParticipant_cancelsGraceAndBroadcastsQuitStatus() {
        BattleRoom room = playingRoom();
        Member member = mockMember();
        BattleParticipant participant = abandonedParticipant(room, member);

        givenRoomMemberParticipant(room, member, participant, List.of(participant));

        withAfterCommit(() -> sut.exitRoom(ROOM_ID, MEMBER_ID));

        verify(reconnectStore).cancelGracePeriod(MEMBER_ID);
        assertThat(participant.getStatus()).isEqualTo(BattleParticipantStatus.QUIT);
        verify(publisher)
                .publish(
                        eq("/topic/room/" + ROOM_ID),
                        eq(Map.of(
                                "type",
                                "PARTICIPANT_STATUS_CHANGED",
                                "userId",
                                MEMBER_ID,
                                "status",
                                BattleParticipantStatus.QUIT.name())));
    }

    @Test
    @DisplayName("마지막 활성 참여자가 퇴장하면 즉시 정산한다")
    void exitRoom_lastActiveParticipant_settlesImmediately() {
        BattleRoom room = playingRoom();
        Member member = mockMember();
        BattleParticipant participant = playingParticipant(room, member);

        BattleParticipant exitedOther = mock(BattleParticipant.class);
        when(exitedOther.getStatus()).thenReturn(BattleParticipantStatus.SOLVED);

        givenRoomMemberParticipant(room, member, participant, List.of(participant, exitedOther));

        withAfterCommit(() -> sut.exitRoom(ROOM_ID, MEMBER_ID));

        verify(battleResultService).settle(ROOM_ID);
    }

    @Test
    @DisplayName("활성 참여자가 남아있으면 퇴장해도 정산하지 않는다")
    void exitRoom_activeParticipantRemains_doesNotSettle() {
        BattleRoom room = playingRoom();
        Member member = mockMember();
        BattleParticipant participant = playingParticipant(room, member);

        BattleParticipant activeOther = mock(BattleParticipant.class);
        when(activeOther.getStatus()).thenReturn(BattleParticipantStatus.PLAYING);

        givenRoomMemberParticipant(room, member, participant, List.of(participant, activeOther));

        withAfterCommit(() -> sut.exitRoom(ROOM_ID, MEMBER_ID));

        verify(battleResultService, never()).settle(any());
    }

    @Test
    @DisplayName("ABANDONED 참여자만 남아있으면 퇴장해도 정산하지 않는다")
    void exitRoom_onlyAbandonedParticipantRemains_doesNotSettle() {
        BattleRoom room = playingRoom();
        Member member = mockMember();
        BattleParticipant participant = playingParticipant(room, member);

        BattleParticipant abandonedOther = mock(BattleParticipant.class);
        when(abandonedOther.getStatus()).thenReturn(BattleParticipantStatus.ABANDONED);

        givenRoomMemberParticipant(room, member, participant, List.of(participant, abandonedOther));

        withAfterCommit(() -> sut.exitRoom(ROOM_ID, MEMBER_ID));

        verify(battleResultService, never()).settle(any());
    }

    @Test
    @DisplayName("PLAYING 상태가 아닌 방에서 exitRoom 호출하면 예외가 발생한다")
    void exitRoom_nonPlayingRoom_throws() {
        BattleRoom room = mock(BattleRoom.class);
        when(room.getStatus()).thenReturn(BattleRoomStatus.FINISHED);
        when(battleRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));

        assertThatThrownBy(() -> sut.exitRoom(ROOM_ID, MEMBER_ID)).isInstanceOf(ServiceException.class);
    }

    @Test
    @DisplayName("SOLVED 상태 참여자가 exitRoom 호출하면 예외가 발생한다")
    void exitRoom_solvedParticipant_throws() {
        BattleRoom room = playingRoom();
        Member member = mockMember();
        BattleParticipant participant = BattleParticipant.create(room, member);
        participant.join();
        participant.complete(LocalDateTime.now());

        givenRoomMemberParticipant(room, member, participant, List.of());

        assertThatThrownBy(() -> sut.exitRoom(ROOM_ID, MEMBER_ID)).isInstanceOf(ServiceException.class);
    }

    @Test
    @DisplayName("QUIT 상태 참여자가 exitRoom 호출하면 예외가 발생한다")
    void exitRoom_quitParticipant_throws() {
        BattleRoom room = playingRoom();
        Member member = mockMember();
        BattleParticipant participant = BattleParticipant.create(room, member);
        participant.join();
        participant.quit();

        givenRoomMemberParticipant(room, member, participant, List.of());

        assertThatThrownBy(() -> sut.exitRoom(ROOM_ID, MEMBER_ID)).isInstanceOf(ServiceException.class);
    }

    private BattleRoom playingRoom() {
        BattleRoom room = mock(BattleRoom.class);
        when(room.getId()).thenReturn(ROOM_ID);
        when(room.getStatus()).thenReturn(BattleRoomStatus.PLAYING);
        return room;
    }

    private Member mockMember() {
        Member member = mock(Member.class);
        when(member.getId()).thenReturn(MEMBER_ID);
        return member;
    }

    private BattleParticipant playingParticipant(BattleRoom room, Member member) {
        BattleParticipant participant = BattleParticipant.create(room, member);
        participant.join();
        return participant;
    }

    private BattleParticipant abandonedParticipant(BattleRoom room, Member member) {
        BattleParticipant participant = BattleParticipant.create(room, member);
        participant.join();
        participant.abandon();
        return participant;
    }

    private void givenRoomMemberParticipant(
            BattleRoom room, Member member, BattleParticipant participant, List<BattleParticipant> all) {
        when(battleRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(battleParticipantRepository.findByBattleRoomAndMember(room, member))
                .thenReturn(Optional.of(participant));
        when(battleParticipantRepository.save(any())).thenReturn(participant);
        when(battleParticipantRepository.findByBattleRoom(room)).thenReturn(all);
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
