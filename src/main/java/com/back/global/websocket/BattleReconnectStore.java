package com.back.global.websocket;

import java.util.concurrent.TimeUnit;

import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 배틀 중 WebSocket 연결 끊김에 대한 재연결 유예 기간(Grace Period)을 관리.
 *
 * <p>Key 설계:
 * <pre>
 * battle:grace:queue  ZSET → List  — Redisson DelayedQueue 내부 저장소
 * </pre>
 *
 * <p>이전 방식(폴링 스케줄러)과의 차이:
 * 기존에는 5초마다 스케줄러가 ABANDONED 참여자를 폴링하고 left_sent 키로 중복 발행을 방지했다.
 * 현재는 disconnect 시점에 15초 지연 메시지를 큐에 등록하고, GracePeriodConsumer가 정확히
 * 15초 후 한 번만 처리한다. left_sent 키와 스케줄러 폴링이 불필요해진다.
 *
 * <p>RDelayedQueue 구조:
 * RDelayedQueue (ZSET) - offer 시 score = 지금+15초 로 저장
 *   ↓ 15초 후 Redisson 내부 폴링(100ms)이 이동
 * RBlockingQueue (List) - GracePeriodConsumer.take()가 꺼냄
 */
@Component
@RequiredArgsConstructor
public class BattleReconnectStore {

    private final RedissonClient redissonClient;

    @Value("${battle.grace-period-seconds:15}")
    private long gracePeriodSeconds;

    private static final String GRACE_QUEUE = "battle:grace:queue";

    private RBlockingQueue<String> blockingQueue() {
        return redissonClient.getBlockingQueue(GRACE_QUEUE);
    }

    private RDelayedQueue<String> delayedQueue() {
        return redissonClient.getDelayedQueue(blockingQueue());
    }

    /**
     * 유예 기간 시작 — disconnect 시 호출.
     * DelayedQueue에 15초 후 처리할 메시지를 등록한다.
     */
    public void startGracePeriod(Long memberId) {
        delayedQueue().offer(memberId.toString(), gracePeriodSeconds, TimeUnit.SECONDS);
    }

    /**
     * 유예 기간 취소 — 재연결(joinRoom) 또는 의도적 퇴장(exitRoom) 시 호출.
     * 타이밍에 따라 메시지가 ZSET 또는 List에 있을 수 있으므로 양쪽 모두 제거 시도한다.
     */
    public void cancelGracePeriod(Long memberId) {
        delayedQueue().remove(memberId.toString()); // ZSET에 있으면 제거 (offer 직후 ~ 14.9s)
        blockingQueue().remove(memberId.toString()); // 이미 List로 이동했으면 거기서도 제거 (15s ~ take() 전)
    }

    /**
     * GracePeriodConsumer가 15초 후 처리할 메시지를 꺼내는 큐.
     */
    public RBlockingQueue<String> getBlockingQueue() {
        return blockingQueue();
    }
}
