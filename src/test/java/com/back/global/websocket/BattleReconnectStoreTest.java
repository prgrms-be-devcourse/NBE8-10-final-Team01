package com.back.global.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * BattleReconnectStore Testcontainers 통합 테스트.
 *
 * <p>Spring 컨텍스트 없이 RedissonClient를 직접 생성하므로
 * GracePeriodConsumer 소비 스레드의 간섭 없이 큐 동작만 검증한다.
 *
 * BattleReconnectStore 변경
 * - GRACE_SECONDS = 15L 상수 → @Value("${battle.grace-period-seconds:15}") 필드로 변경
 * - 배포 시 기본값 15초 적용, 별도 yml 설정 불필요
 * grace period를 2초로 단축해 테스트 실행 시간을 최소화한다.
 *
 * 설계 포인트
 * - Spring 컨텍스트 없이 Redisson.create() 직접 사용 → GracePeriodConsumer 소비 스레드 간섭 없음
 * - ReflectionTestUtils.setField()로 grace period를 2초로 단축 → 테스트가 4초 안에 완료
 * - flushdb()로 테스트 간 큐 상태 격리
 */
@Testcontainers
class BattleReconnectStoreTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private RedissonClient redissonClient;
    private BattleReconnectStore store;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
        redissonClient = Redisson.create(config);

        store = new BattleReconnectStore(redissonClient);
        ReflectionTestUtils.setField(store, "gracePeriodSeconds", 2L);

        redissonClient.getKeys().flushdb();
    }

    @AfterEach
    void tearDown() {
        redissonClient.shutdown();
    }

    @Test
    @DisplayName("startGracePeriod 호출 후 2초가 지나면 BlockingQueue에서 memberId를 수신한다")
    void startGracePeriod_2초후_BlockingQueue에서_수신된다() throws InterruptedException {
        store.startGracePeriod(1L);

        String received = store.getBlockingQueue().poll(4, TimeUnit.SECONDS);

        assertThat(received).isEqualTo("1");
    }

    @Test
    @DisplayName("cancelGracePeriod 호출 시 만료 후에도 BlockingQueue에서 수신되지 않는다")
    void cancelGracePeriod_만료전취소시_BlockingQueue에서_수신안된다() throws InterruptedException {
        store.startGracePeriod(2L);
        store.cancelGracePeriod(2L);

        String received = store.getBlockingQueue().poll(4, TimeUnit.SECONDS);

        assertThat(received).isNull();
    }
}
