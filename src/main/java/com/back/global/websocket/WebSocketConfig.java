package com.back.global.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    // TODO: STOMP ChannelInterceptor 추가하여 CONNECT 시점에 JWT 토큰 검증 필요 (현재 인증 없이 WebSocket 연결 가능)
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 서버 → 클라이언트 구독 prefix
        registry.enableSimpleBroker("/topic");
        // 클라이언트 → 서버 전송 prefix
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // WebSocket 연결 엔드포인트 (SockJS 폴백 포함)
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*").withSockJS();
    }
}
