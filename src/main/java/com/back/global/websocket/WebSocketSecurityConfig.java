package com.back.global.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.socket.AbstractSecurityWebSocketMessageBrokerConfigurer;

@Configuration
public class WebSocketSecurityConfig extends AbstractSecurityWebSocketMessageBrokerConfigurer {

    /**
     * spring-security-messaging이 클래스패스에 있으면 WebSocket CSRF(동일 출처 검사)가 자동으로 활성화됨.
     * 현재는 SockJS/STOMP 연결을 허용하기 위해 CSRF 검사를 비활성화한 상태.
     *
     * [보안 주의]
     * 현재 WebSocket 인증이 쿠키(accessToken) 기반이므로, sameOriginDisabled() = true는
     * 악의적인 사이트가 사용자 쿠키를 이용해 cross-origin WebSocket 연결을 시도할 수 있는 위험이 있음.
     *
     * TODO: 쿠키 기반 인증을 STOMP CONNECT 헤더(Authorization: Bearer {token}) 방식으로 전환하여
     *       CSRF 위험을 근본적으로 제거하고, sameOriginDisabled()를 false로 되돌릴 것.
     *       - 프론트: Client({ connectHeaders: { Authorization: `Bearer ${token}` } })
     *       - 백엔드: JwtHandshakeInterceptor에서 쿠키 대신 헤더에서 JWT 파싱
     */
    @Override
    protected boolean sameOriginDisabled() {
        return true; // TODO: 헤더 기반 JWT 인증 전환 후 false로 변경
    }
}
