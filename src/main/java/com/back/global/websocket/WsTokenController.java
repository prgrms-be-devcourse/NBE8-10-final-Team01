package com.back.global.websocket;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.back.global.security.SecurityUser;

import lombok.RequiredArgsConstructor;

/**
 * WebSocket 핸드셰이크용 1회용 토큰 발급 API.
 *
 * <p>사용 시나리오 (배포 환경):
 * 1. 클라이언트가 JWT 쿠키로 로그인된 상태에서 이 API를 호출
 * 2. 서버가 1회용 토큰을 발급하고 Redis에 30초 TTL로 저장
 * 3. 클라이언트가 STOMP CONNECT 헤더(X-WS-Token)에 토큰을 포함해 WebSocket 연결
 * 4. ChannelInterceptor가 토큰을 Redis에서 검증하고 즉시 삭제 (1회용)
 *
 * <p>로컬 환경에서는 JWT 쿠키가 자동 전송되므로 이 API 호출 없이도 연결 가능.
 */
@RestController
@RequestMapping("/api/v1/ws")
@RequiredArgsConstructor
public class WsTokenController {

    private final WsTokenStore wsTokenStore;

    /**
     * WebSocket 연결용 1회용 토큰 발급.
     *
     * <p>요청 조건: 로그인된 사용자 (JWT 쿠키 필요, SecurityConfig의 anyRequest().authenticated() 적용)
     *
     * @param user 현재 로그인된 사용자 (JwtAuthenticationFilter가 SecurityContext에 등록)
     * @return {"token": "uuid-xxx"} 형태의 1회용 토큰
     */
    @PostMapping("/token")
    public ResponseEntity<Map<String, String>> issueWsToken(@AuthenticationPrincipal SecurityUser user) {
        String token = wsTokenStore.issue(user);
        return ResponseEntity.ok(Map.of("token", token));
    }
}
