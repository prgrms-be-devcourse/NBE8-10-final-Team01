package com.back.global.websocket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;
import com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus;
import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.battle.battleroom.entity.BattleRoomStatus;
import com.back.global.security.SecurityUser;
import com.back.global.websocket.pubsub.WebSocketMessagePublisher;

class BattleDisconnectHandlerTest {

    private final BattleParticipantRepository battleParticipantRepository = mock(BattleParticipantRepository.class);
    private final BattleReconnectStore reconnectStore = mock(BattleReconnectStore.class);
    private final WebSocketMessagePublisher publisher = mock(WebSocketMessagePublisher.class);

    private final BattleDisconnectHandler sut =
            new BattleDisconnectHandler(battleParticipantRepository, reconnectStore, publisher);

    private static final Long MEMBER_ID = 10L;
    private static final Long ROOM_ID = 1L;

    @Test
    @DisplayName("PLAYING 참여자 disconnect시 ABANDONED로 변경되고 grace period가 시작된다")
    void handleDisconnect_PLAYING참여자_abandon_후_gracePeriod시작() {
        SessionDisconnectEvent event = mockAuthenticatedEvent(MEMBER_ID);

        BattleRoom room = mock(BattleRoom.class);
        when(room.getId()).thenReturn(ROOM_ID);

        BattleParticipant participant =
                BattleParticipant.create(room, mock(com.back.domain.member.member.entity.Member.class));
        participant.join();

        when(battleParticipantRepository.findPlayingParticipantByMemberId(
                        MEMBER_ID, BattleParticipantStatus.PLAYING, BattleRoomStatus.PLAYING))
                .thenReturn(Optional.of(participant));

        withAfterCommit(() -> sut.handleDisconnect(event));

        com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus status = participant.getStatus();
        org.assertj.core.api.Assertions.assertThat(status).isEqualTo(BattleParticipantStatus.ABANDONED);
        verify(reconnectStore).startGracePeriod(MEMBER_ID);
        verify(battleParticipantRepository).save(participant);
        verify(publisher)
                .publish(
                        "/topic/room/" + ROOM_ID,
                        Map.of(
                                "type",
                                "PARTICIPANT_STATUS_CHANGED",
                                "userId",
                                MEMBER_ID,
                                "status",
                                BattleParticipantStatus.ABANDONED.name()));
    }

    @Test
    @DisplayName("미인증 세션의 disconnect는 무시된다")
    void handleDisconnect_미인증세션_무시() {
        SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
        when(event.getUser()).thenReturn(null);
        when(event.getSessionId()).thenReturn("unauthenticated-session");

        withAfterCommit(() -> sut.handleDisconnect(event));

        verify(battleParticipantRepository, never()).findPlayingParticipantByMemberId(any(), any(), any());
        verify(reconnectStore, never()).startGracePeriod(any());
    }

    @Test
    @DisplayName("배틀 중인 방이 없는 유저의 disconnect는 무시된다")
    void handleDisconnect_배틀중인방없으면_무시() {
        SessionDisconnectEvent event = mockAuthenticatedEvent(MEMBER_ID);

        when(battleParticipantRepository.findPlayingParticipantByMemberId(
                        MEMBER_ID, BattleParticipantStatus.PLAYING, BattleRoomStatus.PLAYING))
                .thenReturn(Optional.empty());

        sut.handleDisconnect(event);

        verify(reconnectStore, never()).startGracePeriod(any());
        verify(battleParticipantRepository, never()).save(any());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private SessionDisconnectEvent mockAuthenticatedEvent(Long memberId) {
        SecurityUser securityUser = mock(SecurityUser.class);
        when(securityUser.getId()).thenReturn(memberId);

        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(securityUser, null, Collections.emptyList());

        SessionDisconnectEvent event = mock(SessionDisconnectEvent.class);
        when(event.getUser()).thenReturn(auth);
        return event;
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
