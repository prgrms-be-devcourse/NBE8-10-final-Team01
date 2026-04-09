package com.back.domain.battle.battleroom.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;
import com.back.domain.battle.battleroom.entity.BattleRoom;

public record JoinRoomResponse(
        Long roomId, String status, LocalDateTime timerEnd, List<RoomResponse.ParticipantInfo> participants) {

    public static JoinRoomResponse from(BattleRoom room, List<BattleParticipant> participants) {
        return new JoinRoomResponse(
                room.getId(),
                room.getStatus().name(),
                room.getTimerEnd(),
                participants.stream().map(RoomResponse.ParticipantInfo::from).toList());
    }
}
