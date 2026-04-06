package com.back.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.global.security.SecurityUser;

class BattleRoomSubscribeInterceptorTest {

    private final BattleParticipantRepository battleParticipantRepository = mock(BattleParticipantRepository.class);
    private final BattleRoomSubscribeInterceptor interceptor =
            new BattleRoomSubscribeInterceptor(battleParticipantRepository);
    private final MessageChannel channel = mock(MessageChannel.class);

    private static final Long ROOM_ID = 1L;
    private static final Long MEMBER_ID = 10L;

    @Test
    @DisplayName("참여자가 /topic/room/{roomId} 구독 시 메시지가 통과된다")
    void subscribe_참여자_통과() {
        when(battleParticipantRepository.existsByBattleRoomIdAndMemberId(ROOM_ID, MEMBER_ID))
                .thenReturn(true);
        Message<?> message = subscribeMessage("/topic/room/" + ROOM_ID, authenticatedUser(MEMBER_ID));

        Message<?> result = interceptor.preSend(message, channel);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("비참여자가 /topic/room/{roomId} 구독 시 null을 반환해 구독을 차단한다")
    void subscribe_비참여자_차단() {
        when(battleParticipantRepository.existsByBattleRoomIdAndMemberId(ROOM_ID, MEMBER_ID))
                .thenReturn(false);
        Message<?> message = subscribeMessage("/topic/room/" + ROOM_ID, authenticatedUser(MEMBER_ID));

        Message<?> result = interceptor.preSend(message, channel);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("인증 정보 없이 /topic/room/{roomId} 구독 시 null을 반환해 구독을 차단한다")
    void subscribe_인증없음_차단() {
        Message<?> message = subscribeMessage("/topic/room/" + ROOM_ID, null);

        Message<?> result = interceptor.preSend(message, channel);

        assertThat(result).isNull();
        verify(battleParticipantRepository, never()).existsByBattleRoomIdAndMemberId(ROOM_ID, MEMBER_ID);
    }

    @Test
    @DisplayName("/topic/room/{roomId}/spectate 구독 시 참여자 여부 확인 없이 통과된다")
    void subscribe_spectate채널_통과() {
        Message<?> message = subscribeMessage("/topic/room/" + ROOM_ID + "/spectate", authenticatedUser(MEMBER_ID));

        Message<?> result = interceptor.preSend(message, channel);

        assertThat(result).isNotNull();
        verify(battleParticipantRepository, never()).existsByBattleRoomIdAndMemberId(ROOM_ID, MEMBER_ID);
    }

    @Test
    @DisplayName("SUBSCRIBE 아닌 커맨드는 참여자 확인 없이 통과된다")
    void nonSubscribeCommand_통과() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/room/" + ROOM_ID + "/code");
        accessor.setSessionId("session1");
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, channel);

        assertThat(result).isNotNull();
        verify(battleParticipantRepository, never()).existsByBattleRoomIdAndMemberId(ROOM_ID, MEMBER_ID);
    }

    @Test
    @DisplayName("/topic/matching 같은 다른 채널 구독 시 참여자 확인 없이 통과된다")
    void subscribe_다른채널_통과() {
        Message<?> message = subscribeMessage("/topic/matching", authenticatedUser(MEMBER_ID));

        Message<?> result = interceptor.preSend(message, channel);

        assertThat(result).isNotNull();
        verify(battleParticipantRepository, never()).existsByBattleRoomIdAndMemberId(ROOM_ID, MEMBER_ID);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private Message<?> subscribeMessage(String destination, UsernamePasswordAuthenticationToken principal) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setSessionId("session1");
        if (principal != null) {
            accessor.setUser(principal);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private UsernamePasswordAuthenticationToken authenticatedUser(Long memberId) {
        SecurityUser securityUser = new SecurityUser(memberId, "user@test.com", "user", "ROLE_USER");
        return new UsernamePasswordAuthenticationToken(securityUser, null, securityUser.getAuthorities());
    }
}
