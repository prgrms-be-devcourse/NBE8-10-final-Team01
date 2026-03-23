package com.back.domain.battle.result.dto;

import com.back.domain.battle.battleroom.entity.BattleRoom;

public record RoomListResponse(Long roomId, String status, String problemTitle, int currentPlayers, int maxPlayers) {

    public static RoomListResponse from(BattleRoom room, int currentPlayers) {
        return new RoomListResponse(
                room.getId(),
                room.getStatus().name(),
                room.getProblem().getTitle(),
                currentPlayers,
                room.getMaxPlayers());
    }
}
