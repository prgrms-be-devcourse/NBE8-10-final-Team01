package com.back.global.redis;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.RequiredArgsConstructor;

/**
 * RedissonClient 빈 설정.
 *
 * <p>@Value("${spring.data.redis.host}") 대신 RedisConnectionDetails를 주입받는 이유:
 * 테스트 환경에서는 @ServiceConnection이 RedisConnectionDetails 빈으로 연결 정보를 제공하며,
 * 이 값이 Spring Environment 프로퍼티로 노출되지 않는다.
 * RedisConnectionDetails를 직접 주입하면 운영(application-dev.yml)과
 * 테스트(@ServiceConnection) 양쪽에서 모두 올바른 연결 정보를 사용할 수 있다.
 */
@Configuration
@RequiredArgsConstructor
public class RedissonConfig {

    private final RedisConnectionDetails redisConnectionDetails;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        RedisConnectionDetails.Standalone standalone = redisConnectionDetails.getStandalone();
        String host = standalone.getHost();
        int port = standalone.getPort();

        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + host + ":" + port);
        return Redisson.create(config);
    }
}
