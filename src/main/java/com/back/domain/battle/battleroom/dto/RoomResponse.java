package com.back.domain.battle.battleroom.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;
import com.back.domain.battle.battleroom.entity.BattleRoom;

public record RoomResponse(
        Long roomId,
        Long problemId,
        String status,
        int maxPlayers,
        LocalDateTime timerEnd,
        List<ParticipantInfo> participants) {

    public record ParticipantInfo(Long userId, String nickname, String status) {
        public static ParticipantInfo from(BattleParticipant p) {
            return new ParticipantInfo(
                    p.getMember().getId(),
                    p.getMember().getNickname(),
                    p.getStatus().name());
        }
    }

    public static RoomResponse from(BattleRoom room, List<BattleParticipant> participants) {
        return new RoomResponse(
                room.getId(),
                room.getProblem().getId(),
                room.getStatus().name(),
                room.getMaxPlayers(),
                room.getTimerEnd(),
                participants.stream().map(ParticipantInfo::from).toList());
    }
}
