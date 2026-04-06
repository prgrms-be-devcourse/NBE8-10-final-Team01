package com.back.global.websocket;

import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;

import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.global.security.SecurityUser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * STOMP SUBSCRIBE 시 battleRoom 채널(/topic/room/{roomId}) 구독 권한을 검증하는 인터셉터.
 *
 * <p>참여자 전용 채널인 /topic/room/{roomId}는 해당 방의 BattleParticipant만 구독 가능.
 * 관전자 채널(/topic/room/{roomId}/spectate)은 패턴에 걸리지 않으므로 그대로 통과.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BattleRoomSubscribeInterceptor implements ChannelInterceptor {

    private static final Pattern BATTLE_ROOM_TOPIC = Pattern.compile("^/topic/room/(\\d+)$");

    private final BattleParticipantRepository battleParticipantRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || !StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            return message;
        }

        String destination = accessor.getDestination();
        if (destination == null) {
            return message;
        }

        Matcher matcher = BATTLE_ROOM_TOPIC.matcher(destination);
        if (!matcher.matches()) {
            return message;
        }

        Long roomId = Long.parseLong(matcher.group(1));
        Principal principal = accessor.getUser();

        if (!(principal instanceof UsernamePasswordAuthenticationToken auth)
                || !(auth.getPrincipal() instanceof SecurityUser securityUser)) {
            log.warn("WebSocket 구독 거부 - 인증 정보 없음 destination={}", destination);
            return null;
        }

        if (!battleParticipantRepository.existsByBattleRoomIdAndMemberId(roomId, securityUser.getId())) {
            log.warn("WebSocket 구독 거부 - 비참여자 memberId={} roomId={}", securityUser.getId(), roomId);
            return null;
        }

        return message;
    }
}
