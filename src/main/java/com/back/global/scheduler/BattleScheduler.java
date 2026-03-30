package com.back.global.scheduler;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.battle.battleroom.entity.BattleRoomStatus;
import com.back.domain.battle.battleroom.repository.BattleRoomRepository;
import com.back.domain.battle.result.service.BattleResultService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class BattleScheduler {

    private final BattleRoomRepository battleRoomRepository;
    private final BattleResultService battleResultService;

    /**
     * 10초마다 타이머가 만료된 PLAYING 방을 조회해 결과 정산
     * BattleResultService.settle() 내부에서 FINISHED 체크로 중복 정산 방지
     */
    @Scheduled(fixedDelay = 10_000_000)
    public void checkExpiredRooms() {
        List<BattleRoom> expiredRooms =
                battleRoomRepository.findByStatusAndTimerEndBefore(BattleRoomStatus.PLAYING, LocalDateTime.now());

        for (BattleRoom room : expiredRooms) {
            log.info("타이머 만료 감지 - roomId: {}, timerEnd: {}", room.getId(), room.getTimerEnd());
            battleResultService.settle(room.getId());
        }
    }
}
