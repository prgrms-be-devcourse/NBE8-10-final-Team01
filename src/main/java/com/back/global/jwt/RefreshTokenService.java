package com.back.global.jwt;

import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final String KEY_PREFIX = "RT:";

    private final StringRedisTemplate redisTemplate;
    private final JwtProvider jwtProvider;

    public void save(Long memberId, String refreshToken) {
        log.debug("Redis save: key={}, ttl={}ms", KEY_PREFIX + memberId, jwtProvider.getRefreshExpiration());
        redisTemplate
                .opsForValue()
                .set(KEY_PREFIX + memberId, refreshToken, jwtProvider.getRefreshExpiration(), TimeUnit.MILLISECONDS);
        log.debug("Redis save complete: key={}", KEY_PREFIX + memberId);
    }

    public String get(Long memberId) {
        return redisTemplate.opsForValue().get(KEY_PREFIX + memberId);
    }

    public void delete(Long memberId) {
        redisTemplate.delete(KEY_PREFIX + memberId);
    }
}
