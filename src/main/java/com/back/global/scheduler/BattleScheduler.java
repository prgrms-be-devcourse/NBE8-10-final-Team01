package com.back.global.scheduler;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.battle.battleroom.entity.BattleRoomStatus;
import com.back.domain.battle.battleroom.repository.BattleRoomRepository;
import com.back.domain.battle.result.service.BattleResultService;

import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class BattleScheduler {

    private final BattleRoomRepository battleRoomRepository;
    private final BattleResultService battleResultService;

    /**
     * 안전망: BattleTimerConsumer(Redisson DelayedQueue)가 주 트리거이며,
     * 컨슈머 처리 실패 또는 서버 재시작으로 누락된 방을 60초마다 복구한다.
     * settle() 내부의 FINISHED 체크로 중복 정산을 방지한다.
     */
    @Scheduled(fixedDelay = 60_000)
    public void checkExpiredRooms() {
        List<BattleRoom> expiredRooms =
                battleRoomRepository.findByStatusAndTimerEndBefore(BattleRoomStatus.PLAYING, LocalDateTime.now());

        for (BattleRoom room : expiredRooms) {
            log.info("타이머 만료 감지 - roomId: {}, timerEnd: {}", room.getId(), room.getTimerEnd());
            try {
                battleResultService.settle(room.getId());
            } catch (OptimisticLockException e) {
                log.info("settle 낙관적 락 충돌 - 이미 정산 완료됨 roomId={}", room.getId());
            } catch (Exception e) {
                log.error("settle 실패 roomId={}", room.getId(), e);
            }
        }
    }
}
