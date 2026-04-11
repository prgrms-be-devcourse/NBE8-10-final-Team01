package com.back.global.websocket;

import java.util.Map;

import org.redisson.RedissonShutdownException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus;
import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.domain.battle.battleroom.entity.BattleRoomStatus;
import com.back.global.websocket.pubsub.WebSocketMessagePublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Grace Period 만료 메시지를 소비하는 백그라운드 컨슈머.
 *
 * Redisson DelayedQueue에 등록된 메시지가 15초 후 BlockingQueue로 이동하면
 * 이 컨슈머가 꺼내서 PARTICIPANT_STATUS_CHANGED(ABANDONED) 브로드캐스트 여부를 결정한다.
 *
 * 처리 흐름:
 * blockingQueue.take() → memberId 수신
 *   → DB 조회: 아직 ABANDONED 상태인지 확인
 *   → ABANDONED → PARTICIPANT_STATUS_CHANGED(ABANDONED) 브로드캐스트
 *   → PLAYING   → 이미 재접속함, 스킵
 *
 *
 * DB 조회를 거치는 이유:
 * cancelGracePeriod()가 타이밍 상 blockingQueue에서 항목을 제거하지 못한 경우에도
 * 이미 재접속한 참여자에게 PARTICIPANT_STATUS_CHANGED(ABANDONED)가 잘못 발행되는 것을 방지한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GracePeriodConsumer {

    private final BattleReconnectStore reconnectStore;
    private final BattleParticipantRepository battleParticipantRepository;
    private final WebSocketMessagePublisher publisher;

    @EventListener(ApplicationReadyEvent.class)
    public void startConsuming() {
        Thread.ofVirtual().name("grace-period-consumer").start(this::consume);
    }

    private void consume() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                String item = reconnectStore.getBlockingQueue().take(); // blockingQueue.take() → memberId 수신
                Long memberId = Long.parseLong(item);
                handle(memberId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("GracePeriodConsumer 종료");
                break;
            } catch (RedissonShutdownException e) {
                log.info("Redisson 종료 감지 - GracePeriodConsumer 중단");
                break;
            } catch (Exception e) {
                log.error("GracePeriodConsumer 처리 중 오류", e);
            }
        }
    }

    // package-private for testing
    void handle(Long memberId) {
        battleParticipantRepository
                .findAbandonedParticipantByMemberId(
                        memberId, BattleParticipantStatus.ABANDONED, BattleRoomStatus.PLAYING)
                .ifPresentOrElse(
                        p -> {
                            Long roomId = p.getBattleRoom().getId();
                            log.info(
                                    "grace period 만료 - PARTICIPANT_STATUS_CHANGED(ABANDONED) 전송 memberId={}, roomId={}",
                                    memberId,
                                    roomId);
                            publisher.publish(
                                    "/topic/room/" + roomId,
                                    Map.of(
                                            "type",
                                            "PARTICIPANT_STATUS_CHANGED",
                                            "userId",
                                            memberId,
                                            "status",
                                            "ABANDONED"));
                        },
                        () -> log.debug("grace period 만료 - 이미 재접속함, 스킵 memberId={}", memberId));
    }
}
