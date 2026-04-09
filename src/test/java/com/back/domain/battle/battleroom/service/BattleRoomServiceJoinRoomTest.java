package com.back.domain.battle.battleroom.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;
import com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus;
import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.domain.battle.battleroom.dto.JoinRoomResponse;
import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.battle.battleroom.repository.BattleRoomRepository;
import com.back.domain.battle.result.service.BattleResultService;
import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.repository.MemberRepository;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.problem.problem.repository.ProblemRepository;
import com.back.global.websocket.BattleCodeStore;
import com.back.global.websocket.BattleReconnectStore;
import com.back.global.websocket.BattleTimerStore;
import com.back.global.websocket.pubsub.WebSocketMessagePublisher;

class BattleRoomServiceJoinRoomTest {

    private static final Long ROOM_ID = 1L;
    private static final Long MEMBER_ID = 10L;

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

    @Test
    @DisplayName("READY 참여자가 입장하면 PLAYING 상태 이벤트를 발행한다")
    void joinRoom_readyParticipant_broadcastsPlayingStatus() {
        BattleRoom room = waitingRoom();
        Member member = member();
        BattleParticipant participant = BattleParticipant.create(room, member);

        stubCommon(room, member, participant);
        when(battleParticipantRepository.findByBattleRoom(room)).thenReturn(List.of(participant), List.of(participant));

        JoinRoomResponse response = withAfterCommit(() -> sut.joinRoom(ROOM_ID, MEMBER_ID));

        assertThat(participant.getStatus()).isEqualTo(BattleParticipantStatus.PLAYING);
        assertThat(response.participants()).hasSize(1);
        verify(publisher)
                .publish(
                        eq("/topic/room/" + ROOM_ID),
                        eq(Map.of(
                                "type",
                                "PARTICIPANT_STATUS_CHANGED",
                                "userId",
                                MEMBER_ID,
                                "status",
                                BattleParticipantStatus.PLAYING.name())));
    }

    @Test
    @DisplayName("ABANDONED 참여자가 재입장하면 grace period를 취소하고 PLAYING 상태 이벤트를 발행한다")
    void joinRoom_abandonedParticipant_cancelsGracePeriodAndBroadcastsPlayingStatus() {
        BattleRoom room = playingRoom();
        Member member = member();
        BattleParticipant participant = BattleParticipant.create(room, member);
        participant.join();
        participant.abandon();

        stubCommon(room, member, participant);
        when(battleParticipantRepository.findByBattleRoom(room)).thenReturn(List.of(participant));

        withAfterCommit(() -> sut.joinRoom(ROOM_ID, MEMBER_ID));

        verify(reconnectStore).cancelGracePeriod(MEMBER_ID);
        assertThat(participant.getStatus()).isEqualTo(BattleParticipantStatus.PLAYING);
        verify(publisher)
                .publish(
                        eq("/topic/room/" + ROOM_ID),
                        eq(Map.of(
                                "type",
                                "PARTICIPANT_STATUS_CHANGED",
                                "userId",
                                MEMBER_ID,
                                "status",
                                BattleParticipantStatus.PLAYING.name())));
    }

    @Test
    @DisplayName("마지막 READY 참여자가 입장하면 PLAYING 상태 이벤트와 BATTLE_STARTED를 함께 발행한다")
    void joinRoom_lastReadyParticipant_broadcastsPlayingStatusAndBattleStarted() {
        BattleRoom room = waitingRoom();
        Member me = member();
        Member otherMember1 = mock(Member.class);
        Member otherMember2 = mock(Member.class);
        Member otherMember3 = mock(Member.class);
        when(otherMember1.getNickname()).thenReturn("u1");
        when(otherMember2.getNickname()).thenReturn("u2");
        when(otherMember3.getNickname()).thenReturn("u3");

        BattleParticipant mine = BattleParticipant.create(room, me);
        BattleParticipant p1 = BattleParticipant.create(room, otherMember1);
        BattleParticipant p2 = BattleParticipant.create(room, otherMember2);
        BattleParticipant p3 = BattleParticipant.create(room, otherMember3);
        p1.join();
        p2.join();
        p3.join();

        stubCommon(room, me, mine);
        when(battleParticipantRepository.findByBattleRoom(room))
                .thenReturn(List.of(p1, p2, p3, mine), List.of(p1, p2, p3, mine));

        withAfterCommit(() -> sut.joinRoom(ROOM_ID, MEMBER_ID));

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
                                BattleParticipantStatus.PLAYING.name())));
        verify(publisher)
                .publish(
                        eq("/topic/room/" + ROOM_ID),
                        eq(Map.of(
                                "type",
                                "BATTLE_STARTED",
                                "timerEnd",
                                room.getTimerEnd().toString())));
        verify(battleTimerStore).schedule(ROOM_ID);
    }

    private void stubCommon(BattleRoom room, Member member, BattleParticipant participant) {
        when(battleRoomRepository.findByIdWithLock(ROOM_ID)).thenReturn(Optional.of(room));
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(battleParticipantRepository.findByBattleRoomAndMember(room, member))
                .thenReturn(Optional.of(participant));
        when(battleParticipantRepository.save(any())).thenReturn(participant);
    }

    private BattleRoom waitingRoom() {
        Problem problem = mock(Problem.class);
        when(problem.getId()).thenReturn(100L);
        return BattleRoom.create(problem, 4);
    }

    private BattleRoom playingRoom() {
        BattleRoom room = waitingRoom();
        room.startBattle(java.time.Duration.ofMinutes(30));
        return room;
    }

    private Member member() {
        Member member = mock(Member.class);
        when(member.getId()).thenReturn(MEMBER_ID);
        when(member.getNickname()).thenReturn("user");
        return member;
    }

    private <T> T withAfterCommit(Supplier<T> action) {
        try (MockedStatic<TransactionSynchronizationManager> tsm =
                mockStatic(TransactionSynchronizationManager.class)) {
            tsm.when(() -> TransactionSynchronizationManager.registerSynchronization(any()))
                    .thenAnswer(inv -> {
                        inv.<TransactionSynchronization>getArgument(0).afterCommit();
                        return null;
                    });
            return action.get();
        }
    }
}
