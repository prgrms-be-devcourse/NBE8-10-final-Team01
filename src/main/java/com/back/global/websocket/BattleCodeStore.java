package com.back.global.websocket;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Redis에 코드를 저장/조회/삭제하는 컴포넌트
 * Key:   "battle:code:{roomId}:{memberId}"   예) "battle:code:1:42"
 * Value: 유저가 작성 중인 코드 문자열
 * TTL:   40분 (배틀 시간 30분 + 여유분)
 * TTL을 설정해두면 배틀이 끝난 후 별도로 삭제 안 해도 Redis에서 자동으로 삭제됨
 */
@Component
@RequiredArgsConstructor
public class BattleCodeStore {

    private final StringRedisTemplate redisTemplate;

    private static final String KEY_FORMAT = "battle:code:%d:%d"; // roomId:memberId
    private static final Duration TTL = Duration.ofMinutes(40);

    public void save(Long roomId, Long memberId, String code) {
        redisTemplate.opsForValue().set(KEY_FORMAT.formatted(roomId, memberId), code, TTL);
    }

    public String get(Long roomId, Long memberId) {
        String value = redisTemplate.opsForValue().get(KEY_FORMAT.formatted(roomId, memberId));
        return value != null ? value : "";
    }

    public void delete(Long roomId, Long memberId) {
        redisTemplate.delete(KEY_FORMAT.formatted(roomId, memberId));
    }
}
