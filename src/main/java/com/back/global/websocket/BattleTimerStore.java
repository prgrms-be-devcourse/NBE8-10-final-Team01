package com.back.global.websocket;

import java.util.concurrent.TimeUnit;

import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * 배틀 종료 타이머를 Redisson DelayedQueue로 관리.
 *
 * Key 설계:
 * battle:timer:queue  ZSET → List  — Redisson DelayedQueue 내부 저장소
 *
 * BattleReconnectStore(grace period)와 동일한 패턴:
 * - BattleReconnectStore : memberId → 15초 후 PARTICIPANT_STATUS_CHANGED(ABANDONED)
 * - BattleTimerStore     : roomId  → 30분 후 settle()
 *
 * 기존 BattleScheduler(폴링)와의 역할 분담:
 * - BattleTimerStore  : 배틀 시작 시점에 정확한 종료 시각을 예약 (주 트리거)
 * - BattleScheduler   : 서버 재시작 또는 컨슈머 처리 실패 시 복구용 안전망 (60초 주기)
 */
@Component
@RequiredArgsConstructor
public class BattleTimerStore {

    private final RedissonClient redissonClient;

    @Value("${battle.duration-minutes:30}")
    private long battleDurationMinutes;

    private static final String TIMER_QUEUE = "battle:timer:queue";

    private RBlockingQueue<String> blockingQueue() {
        return redissonClient.getBlockingQueue(TIMER_QUEUE);
    }

    private RDelayedQueue<String> delayedQueue() {
        return redissonClient.getDelayedQueue(blockingQueue());
    }

    /**
     * 배틀 시작 시 호출. 30분 후 settle()이 실행되도록 roomId를 큐에 등록한다.
     * BattleRoomService.joinRoom() afterCommit에서 호출 — DB 커밋 후 예약으로 원자성 보장.
     */
    public void schedule(Long roomId) {
        delayedQueue().offer(roomId.toString(), battleDurationMinutes, TimeUnit.MINUTES);
    }

    /**
     * 전원 AC 조기종료 또는 모든 참여자 퇴장 시 호출.
     * ZSET·List 양쪽 모두 제거 시도 — 이미 컨슈머가 꺼낸 경우 remove()는 no-op.
     */
    public void cancel(Long roomId) {
        delayedQueue().remove(roomId.toString());
        blockingQueue().remove(roomId.toString());
    }

    /**
     * BattleTimerConsumer가 만료된 roomId를 꺼내는 큐.
     */
    public RBlockingQueue<String> getBlockingQueue() {
        return blockingQueue();
    }
}
