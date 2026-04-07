package com.back.global.websocket;

import java.time.Duration;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Redis Hash에 코드를 저장/조회/삭제하는 컴포넌트
 * Key:   "battle:code:{roomId}"              예) "battle:code:1"
 * Field: "{memberId}"                         예) "42"
 * Value: 유저가 작성 중인 코드 문자열
 * TTL:   40분 (배틀 시간 30분 + 여유분)
 *
 * Hash 구조를 사용하면 방 종료 시 DEL 명령 1번으로 모든 참가자 코드를 일괄 삭제 가능
 */
@Component
@RequiredArgsConstructor
public class BattleCodeStore {

    private final StringRedisTemplate redisTemplate;

    private static final String KEY_FORMAT = "battle:code:%d"; // roomId
    private static final Duration TTL = Duration.ofMinutes(40);

    public void save(Long roomId, Long memberId, String code) {
        String key = KEY_FORMAT.formatted(roomId);
        redisTemplate.opsForHash().put(key, memberId.toString(), code);
        redisTemplate.expire(key, TTL);
    }

    public String get(Long roomId, Long memberId) {
        Object value = redisTemplate.opsForHash().get(KEY_FORMAT.formatted(roomId), memberId.toString());
        return value != null ? value.toString() : "";
    }

    public void delete(Long roomId, Long memberId) {
        redisTemplate.opsForHash().delete(KEY_FORMAT.formatted(roomId), memberId.toString());
    }

    /** 방 종료 시 해당 방의 모든 참가자 코드를 일괄 삭제 */
    public void deleteAllByRoom(Long roomId) {
        redisTemplate.delete(KEY_FORMAT.formatted(roomId));
    }
}
