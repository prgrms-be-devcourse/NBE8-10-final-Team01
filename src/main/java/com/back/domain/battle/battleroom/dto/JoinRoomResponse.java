package com.back.domain.battle.battleroom.dto;

import java.time.LocalDateTime;

import com.back.domain.battle.battleroom.entity.BattleRoom;

public record JoinRoomResponse(Long roomId, String status, LocalDateTime timerEnd) {

    public static JoinRoomResponse from(BattleRoom room) {
        return new JoinRoomResponse(room.getId(), room.getStatus().name(), room.getTimerEnd());
    }
}
