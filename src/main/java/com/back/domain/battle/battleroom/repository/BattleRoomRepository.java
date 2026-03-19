package com.back.domain.battle.battleroom.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.back.domain.battle.battleroom.entity.BattleRoom;

public interface BattleRoomRepository extends JpaRepository<BattleRoom, Long> {

    // 타이머 만료된 진행중 방 조회 (스케줄러용)
    List<BattleRoom> findByStatusAndTimerEndBefore(String status, LocalDateTime now);
}
