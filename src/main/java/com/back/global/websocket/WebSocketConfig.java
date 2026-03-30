package com.back.global.websocket;

import java.util.Map;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

import com.back.global.security.SecurityUser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketSessionRegistry sessionRegistry;
    private final JwtHandshakeInterceptor jwtHandshakeInterceptor;

    /**
     * 클라이언트가 /ws로 WebSocket 연결할 때 STOMP CONNECT 프레임이 날아오는데,
     * 그 순간을 가로채서 "이 세션ID는 이 유저꺼다" 라고 등록해두는 것
     * SUBSCRIBE나 SEND 같은 다른 STOMP 커맨드는 CONNECT가 아니므로 그냥 통과함
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                // 메시지가 핸들러로 가기 직전에 실행됨
                StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    // 핸드셰이크 시점에 JwtHandshakeInterceptor가 저장한 memberId를 세션 속성에서 꺼냄
                    Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
                    if (sessionAttributes != null) {
                        Long memberId = (Long) sessionAttributes.get("memberId");
                        if (memberId != null) {
                            // sessionRegistry 등록 (이탈 감지용)
                            sessionRegistry.register(accessor.getSessionId(), memberId);
                            log.info("WebSocket 세션 등록 - sessionId={}, memberId={}", accessor.getSessionId(), memberId);

                            // STOMP 세션 Principal 설정 → @AuthenticationPrincipal이 동작하게 함
                            SecurityUser securityUser =
                                    new SecurityUser(memberId, memberId.toString(), null, "ROLE_USER");
                            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                                    securityUser, null, securityUser.getAuthorities());
                            MessageHeaderAccessor mutableAccessor = MessageHeaderAccessor.getMutableAccessor(message);
                            if (mutableAccessor instanceof StompHeaderAccessor stompAccessor) {
                                stompAccessor.setUser(auth);
                            }
                        } else {
                            log.warn("WebSocket 세션 등록 실패 - 인증 정보 없음. sessionId={}", accessor.getSessionId());
                        }
                    }
                }
                return message; // 메시지를 그대로 통과시킴
            }
        });
    }

    /**
     * heartbeat 설정, 정확히는 hearbeat이 아니라 서버 전송 타임아웃
     * 30초 안에 메시지 전송이 완료 되지 않으면 연결을 끊는다.
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setSendTimeLimit(30 * 1000);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {

        TaskScheduler scheduler = new ThreadPoolTaskScheduler();
        ((ThreadPoolTaskScheduler) scheduler).initialize();

        // 클라이언트가 서버로부터 받기 위해 구독하는 prefix
        registry.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[] {10000, 10000}) // 10초마다 ping/pong
                .setTaskScheduler(scheduler);
        // 서버 → 클라이언트: 10초마다 ping
        // 클라이언트 → 서버: 10초마다 pong
        // 10초 안에 응답 없으면 → 연결 강제 종료 → SessionDisconnectEvent 발생

        // 클라이언트가 서버로 메시지를 보낼 때 쓰는 prefix
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // WebSocket 연결 엔드포인트 (SockJS 폴백 포함)
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .addInterceptors(jwtHandshakeInterceptor)
                .withSockJS();
    }
}
