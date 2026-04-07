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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WsTokenStore wsTokenStore;
    private final BattleRoomSubscribeInterceptor battleRoomSubscribeInterceptor;

    /**
     * STOMP 메시지 인바운드 채널 인터셉터 등록.
     *
     * <p>STOMP CONNECT 시 두 가지 인증 방식을 순서대로 시도:
     *
     * <p>① 쿠키 기반 (로컬 환경):
     *    - JwtAuthenticationFilter가 HTTP 핸드셰이크 요청에서 JWT 쿠키를 검증
     *    - Spring의 DefaultHandshakeHandler가 SecurityContext의 Principal을 WebSocket 세션에 자동 전파
     *    - STOMP CONNECT 도달 시 accessor.getUser()로 이미 인증된 Principal 조회 가능
     *    - 별도 토큰 발급 불필요
     *
     * <p>② 1회용 토큰 기반 (배포 환경):
     *    - cross-origin 환경에서는 SameSite 쿠키 정책으로 쿠키 자동 전송 불가
     *    - 클라이언트가 POST /api/v1/ws/token으로 토큰을 발급받아 STOMP CONNECT 헤더에 포함
     *    - ChannelInterceptor가 X-WS-Token 헤더에서 토큰을 꺼내 Redis에서 검증
     *    - 검증 성공 시 SecurityUser를 생성해 STOMP Principal로 등록, Redis에서 토큰 즉시 삭제
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(
                new ChannelInterceptor() {
                    @Override
                    public Message<?> preSend(Message<?> message, MessageChannel channel) {
                        StompHeaderAccessor accessor =
                                MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
                        if (accessor == null) return message;

                        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
                            Principal principal = accessor.getUser();

                            if (principal instanceof UsernamePasswordAuthenticationToken cookieAuth
                                    && cookieAuth.getPrincipal() instanceof SecurityUser cookieUser) {
                                // ① 쿠키 기반: Spring이 이미 Principal을 전파해둔 상태
                                accessor.setUser(createWsAuthentication(cookieUser));
                                log.info("WebSocket 연결 (쿠키 인증) - memberId={}", cookieUser.getId());

                            } else {
                                // ② 1회용 토큰 기반: STOMP 헤더에서 토큰 추출 후 Redis 검증
                                String token = accessor.getFirstNativeHeader("X-WS-Token");

                                if (token == null) {
                                    // 쿠키도 없고 토큰도 없음 → 연결 거부
                                    log.warn("WebSocket 연결 거부 - 인증 정보 없음 (쿠키/토큰 모두 없음)");
                                    return null;
                                }

                                SecurityUser tokenUser = wsTokenStore.resolve(token);
                                if (tokenUser == null) {
                                    // 만료되었거나 이미 사용된 토큰 → 연결 거부
                                    log.warn("WebSocket 연결 거부 - 유효하지 않거나 만료된 토큰");
                                    return null;
                                }

                                // 검증 성공: SecurityUser를 STOMP Principal로 등록
                                accessor.setUser(createWsAuthentication(tokenUser));
                                log.info("WebSocket 연결 (토큰 인증) - memberId={}", tokenUser.getId());
                            }
                        }

                        return message;
                    }
                },
                battleRoomSubscribeInterceptor);
    }

    private UsernamePasswordAuthenticationToken createWsAuthentication(SecurityUser securityUser) {
        // /user 목적지는 Principal name 으로 대상을 찾기 때문에,
        // matching handoff 는 memberId 기반으로 주소를 맞추도록 이름을 userId 문자열로 고정한다.
        return new UsernamePasswordAuthenticationToken(securityUser, null, securityUser.getAuthorities()) {
            @Override
            public String getName() {
                return String.valueOf(securityUser.getId());
            }
        };
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
        registry.enableSimpleBroker("/topic", "/queue")
                .setHeartbeatValue(new long[] {10000, 10000}) // 10초마다 ping/pong
                .setTaskScheduler(scheduler);
        // 서버 → 클라이언트: 10초마다 ping
        // 클라이언트 → 서버: 10초마다 pong
        // 10초 안에 응답 없으면 → 연결 강제 종료 → SessionDisconnectEvent 발생

        // 클라이언트가 서버로 메시지를 보낼 때 쓰는 prefix
        registry.setApplicationDestinationPrefixes("/app");
        // 사용자별 matching handoff는 /user/queue/matching 으로 보낸다.
        // 예: memberId=1 사용자는 /user/queue/matching 을 구독하고 개인 이벤트만 받는다.
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("https://www.the-bracket.site", "http://localhost:3000", "https://cdpn.io")
                .withSockJS();
    }
}
