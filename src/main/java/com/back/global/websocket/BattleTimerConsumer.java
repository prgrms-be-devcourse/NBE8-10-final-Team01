package com.back.global.websocket;

import org.redisson.RedissonShutdownException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.back.domain.battle.result.service.BattleResultService;

import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 배틀 종료 타이머 만료 메시지를 소비하는 백그라운드 컨슈머.
 *
 * GracePeriodConsumer와 동일한 구조:
 * Redisson DelayedQueue에 등록된 roomId가 30분 후 BlockingQueue로 이동하면
 * 이 컨슈머가 꺼내서 settle()을 호출한다.
 *
 * 처리 실패 시 복구 전략:
 * settle() 예외가 발생해도 BattleScheduler(60초 주기 안전망)가 만료된 방을 재감지해 복구한다.
 * 따라서 여기서는 예외를 삼키고 로그만 남긴다.
 *
 * 멀티 컨슈머 확장:
 * 현재는 단일 가상 스레드로 처리한다.
 * 서비스 규모가 커져 동시에 다수의 방이 만료되는 경우 아래처럼 복수 컨슈머로 확장할 수 있다:
 *
 * for (int i = 0; i < N; i++) {
 *     Thread.ofVirtual().name("battle-timer-consumer-" + i).start(this::consume);
 * }
 *
 * RBlockingQueue는 Redis BLPOP 기반으로 각 아이템을 정확히 하나의 컨슈머만 처리하므로 안전하다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BattleTimerConsumer {

    private final BattleTimerStore battleTimerStore;
    private final BattleResultService battleResultService;

    @EventListener(ApplicationReadyEvent.class)
    public void startConsuming() {
        Thread.ofVirtual().name("battle-timer-consumer").start(this::consume);
    }

    private void consume() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String item = battleTimerStore.getBlockingQueue().take();
                Long roomId = Long.parseLong(item);
                handle(roomId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("BattleTimerConsumer 종료");
                break;
            } catch (RedissonShutdownException e) {
                log.info("Redisson 종료 감지 - BattleTimerConsumer 중단");
                break;
            } catch (Exception e) {
                log.error("BattleTimerConsumer 처리 중 오류", e);
            }
        }
    }

    // package-private for testing
    void handle(Long roomId) {
        try {
            log.info("배틀 타이머 만료 - settle 호출 roomId={}", roomId);
            battleResultService.settle(roomId);
        } catch (OptimisticLockException e) {
            // 동시에 settle()이 두 번 진입한 경우 두 번째 트랜잭션이 여기서 차단됨.
            // 첫 번째가 이미 정산을 완료했으므로 정상 흐름 — 로그만 남기고 무시.
            log.info("settle 낙관적 락 충돌 - 이미 정산 완료됨 roomId={}", roomId);
        } catch (Exception e) {
            // settle() 실패 시 BattleScheduler(60초 주기 안전망)가 복구 예정
            log.error("settle 실패 - 스케줄러 안전망이 복구 예정 roomId={}", roomId, e);
        }
    }
}
