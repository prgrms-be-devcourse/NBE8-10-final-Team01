package com.back.domain.battle.battleroom.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;
import com.back.domain.battle.battleroom.entity.BattleRoom;

public record BattleRoomStateResponse(
        Long roomId,
        Long problemId,
        String status,
        int maxPlayers,
        LocalDateTime timerEnd,
        List<RoomResponse.ParticipantInfo> participants,
        String myCode) {

    public static BattleRoomStateResponse from(BattleRoom room, List<BattleParticipant> participants, String myCode) {
        return new BattleRoomStateResponse(
                room.getId(),
                room.getProblem().getId(),
                room.getStatus().name(),
                room.getMaxPlayers(),
                room.getTimerEnd(),
                participants.stream().map(RoomResponse.ParticipantInfo::from).toList(),
                myCode);
    }
}
