package com.back.global.websocket;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.back.global.security.SecurityUser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * WebSocket 핸드셰이크용 1회용 토큰을 Redis에 저장/조회/삭제하는 저장소.
 *
 * <p>배포 환경에서 cross-origin WebSocket 연결 시 쿠키를 자동 전송할 수 없으므로,
 * 클라이언트는 이 토큰을 발급받아 STOMP CONNECT 헤더(X-WS-Token)로 전달한다.
 *
 * <p>보안 특성:
 * - TTL 30초: 발급 즉시 WebSocket 연결에 사용해야 하며, 미사용 시 자동 만료
 * - 1회용: resolve() 호출 시 Redis에서 즉시 삭제 → 재사용 불가 (토큰 탈취 피해 최소화)
 * - UUID: 추측 불가능한 랜덤 값으로 CSRF 공격 방어
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsTokenStore {

    private static final String KEY_PREFIX = "ws:token:";
    private static final long TTL_SECONDS = 30;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 인증된 사용자 정보를 Redis에 저장하고 1회용 토큰 문자열을 반환.
     *
     * @param user 현재 로그인된 사용자 (JWT 쿠키로 인증됨)
     * @return UUID 형식의 1회용 토큰 (30초 TTL)
     */
    public String issue(SecurityUser user) {
        String token = UUID.randomUUID().toString();

        // role은 SecurityUser에 별도 getter가 없으므로 GrantedAuthority에서 추출
        String role = user.getAuthorities().stream()
                .findFirst()
                .map(a -> a.getAuthority())
                .orElse("ROLE_USER");

        WsTokenPayload payload = new WsTokenPayload(user.getId(), user.getUsername(), user.getName(), role);

        try {
            String json = objectMapper.writeValueAsString(payload);
            redisTemplate.opsForValue().set(KEY_PREFIX + token, json, TTL_SECONDS, TimeUnit.SECONDS);
            log.debug("WS 토큰 발급 - memberId={}, TTL={}s", user.getId(), TTL_SECONDS);
        } catch (JsonProcessingException e) {
            log.error("WS 토큰 발급 실패 - JSON 직렬화 오류", e);
            throw new RuntimeException("WS 토큰 발급 중 오류가 발생했습니다.", e);
        }

        return token;
    }

    /**
     * 토큰을 Redis에서 조회 후 즉시 삭제 (1회용 보장).
     *
     * @param token 클라이언트가 STOMP CONNECT 헤더로 전달한 토큰
     * @return 토큰에 해당하는 SecurityUser, 만료되었거나 존재하지 않으면 null
     */
    public SecurityUser resolve(String token) {
        String key = KEY_PREFIX + token;

        // getAndDelete: 조회와 삭제를 원자적으로 수행 → 동시 요청 시 중복 사용 방지
        String json = redisTemplate.opsForValue().getAndDelete(key);

        if (json == null) {
            log.warn("WS 토큰 조회 실패 - 존재하지 않거나 만료된 토큰");
            return null;
        }

        try {
            WsTokenPayload payload = objectMapper.readValue(json, WsTokenPayload.class);
            return new SecurityUser(payload.id(), payload.username(), payload.name(), payload.role());
        } catch (JsonProcessingException e) {
            log.error("WS 토큰 파싱 실패 - JSON 역직렬화 오류", e);
            return null;
        }
    }

    /**
     * Redis에 저장되는 사용자 정보 페이로드.
     * SecurityUser 재생성에 필요한 최소 필드만 포함.
     */
    record WsTokenPayload(Long id, String username, String name, String role) {}
}
