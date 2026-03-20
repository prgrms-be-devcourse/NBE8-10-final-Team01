package com.back.domain.battle.battleroom.dto;

import com.back.domain.battle.battleroom.entity.BattleRoom;

/**
 * roomId와 status를 반환하는 record
 */
public record CreateRoomResponse(Long roomId, String status) {

    public static CreateRoomResponse from(BattleRoom room) {
        return new CreateRoomResponse(room.getId(), room.getStatus().name());
    }
}
