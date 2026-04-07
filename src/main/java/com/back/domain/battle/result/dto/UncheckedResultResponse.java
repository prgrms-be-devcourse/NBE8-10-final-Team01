package com.back.domain.battle.result.dto;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;

public record UncheckedResultResponse(Long roomId, int rank, long scoreDelta, String problemTitle) {

    public static UncheckedResultResponse from(BattleParticipant p) {
        return new UncheckedResultResponse(
                p.getBattleRoom().getId(),
                p.getFinalRank(),
                p.getScoreDelta(),
                p.getBattleRoom().getProblem().getTitle());
    }
}
