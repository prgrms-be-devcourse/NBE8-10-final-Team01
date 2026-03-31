package com.back.global.websocket;

import java.security.Principal;

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

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * STOMP 메시지가 핸들러로 전달되기 전에 실행되는 인바운드 채널 인터셉터 등록.
     *
     * <p>인증 흐름:
     * 1. JwtAuthenticationFilter가 HTTP 핸드셰이크 요청(GET /ws)에서 JWT 쿠키를 검증하고 SecurityContext에 등록
     * 2. Spring의 DefaultHandshakeHandler가 SecurityContext의 Principal을 WebSocket 세션에 자동 전파
     * 3. STOMP CONNECT 도달 시점에 accessor.getUser()로 인증된 Principal을 바로 조회 가능
     *
     * <p>따라서 JwtHandshakeInterceptor(JWT 재파싱)와 WebSocketSessionRegistry(수동 세션 관리)는 불필요하여 제거됨.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                if (accessor == null) return message;

                if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                    // Spring이 핸드셰이크 시점에 전파한 Principal 조회
                    // SecurityConfig에서 /ws/**를 authenticated()로 설정했으므로
                    // 여기 도달한 요청은 이미 JwtAuthenticationFilter를 통과한 인증된 사용자
                    Principal principal = accessor.getUser();
                    if (principal instanceof UsernamePasswordAuthenticationToken auth
                            && auth.getPrincipal() instanceof SecurityUser user) {
                        log.info("WebSocket 연결 - memberId={}", user.getId());
                    } else {
                        // 정상적이라면 이 경로에 진입하지 않음 (방어적 로그)
                        log.warn("WebSocket 연결 - 인증 정보 없음 (SecurityConfig의 /ws/** 인증 설정 확인 필요)");
                    }
                }

                return message;
            }
        });
    }

    /**
     * 서버 전송 타임아웃 설정.
     * 30초 안에 메시지 전송이 완료되지 않으면 연결을 끊음.
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setSendTimeLimit(30 * 1000);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        TaskScheduler scheduler = new ThreadPoolTaskScheduler();
        ((ThreadPoolTaskScheduler) scheduler).initialize();

        // 클라이언트가 서버로부터 메시지를 받기 위해 구독하는 prefix
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
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                // JwtHandshakeInterceptor 제거:
                // JwtAuthenticationFilter가 HTTP 핸드셰이크 요청에서 JWT 쿠키를 이미 검증하므로
                // 핸드셰이크 인터셉터에서 JWT를 재파싱할 필요 없음
                .withSockJS();
    }
}
