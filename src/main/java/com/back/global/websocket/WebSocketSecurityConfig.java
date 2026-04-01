package com.back.global.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.socket.AbstractSecurityWebSocketMessageBrokerConfigurer;

@Configuration
public class WebSocketSecurityConfig extends AbstractSecurityWebSocketMessageBrokerConfigurer {

    /**
     * Spring Security WebSocket의 동일 출처(Same-Origin) 검사 비활성화.
     *
     * <p>Spring Security는 WebSocket 핸드셰이크 시 Origin 헤더가 서버 도메인과 일치하는지 검사한다.
     * 프론트엔드와 백엔드가 다른 도메인/포트에 배포되는 cross-origin 환경에서는
     * 이 검사가 정상 연결까지 차단하므로 비활성화가 필요하다.
     *
     * <p>비활성화해도 안전한 이유 (CSRF 방어 대체 수단):
     * - ChannelInterceptor가 STOMP CONNECT 단계에서 인증을 강제
     *   - 쿠키 기반: Spring이 전파한 Principal 확인
     *   - 토큰 기반: X-WS-Token 헤더의 1회용 토큰을 Redis에서 검증 후 즉시 삭제
     * - 공격자는 유효한 1회용 토큰을 위조할 수 없고,
     *   토큰 발급 API(POST /api/v1/ws/token)도 JWT 인증이 필요하므로 CSRF 공격 불가
     */
    @Override
    protected boolean sameOriginDisabled() {
        return true;
    }
}
