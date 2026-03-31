package com.back.global.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.socket.AbstractSecurityWebSocketMessageBrokerConfigurer;

@Configuration
public class WebSocketSecurityConfig extends AbstractSecurityWebSocketMessageBrokerConfigurer {

    /**
     * spring-security-messaging이 클래스패스에 있으면 WebSocket CSRF가 자동으로 활성화됨.
     * REST API에서 HTTP CSRF는 이미 비활성화되어 있으므로, WebSocket도 동일하게 처리.
     * true를 반환하면 SockJS/STOMP 연결 시 CSRF 토큰 없이도 접속 가능.
     */
    @Override
    protected boolean sameOriginDisabled() {
        return true;
    }
}
