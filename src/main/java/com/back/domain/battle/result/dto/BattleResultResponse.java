package com.back.domain.battle.result.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;
import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.problem.submission.entity.Submission;

public record BattleResultResponse(Long roomId, String problemTitle, List<ParticipantResult> participants) {

    public record ParticipantResult(
            Long userId,
            String nickname,
            int finalRank,
            long scoreDelta,
            String result,
            int passedCount,
            int totalCount,
            LocalDateTime finishTime) {

        /**
         * @param bestSubmission AC 제출 우선, 없으면 passedCount 가장 높은 제출, 제출 없으면 null
         */
        public static ParticipantResult from(BattleParticipant participant, Submission bestSubmission) {
            return new ParticipantResult(
                    participant.getMember().getId(),
                    participant.getMember().getNickname(),
                    participant.getFinalRank(),
                    participant.getScoreDelta(),
                    bestSubmission != null && bestSubmission.getResult() != null
                            ? bestSubmission.getResult().name()
                            : null,
                    bestSubmission != null && bestSubmission.getPassedCount() != null
                            ? bestSubmission.getPassedCount()
                            : 0,
                    bestSubmission != null && bestSubmission.getTotalCount() != null
                            ? bestSubmission.getTotalCount()
                            : 0,
                    participant.getFinishTime());
        }
    }

    public static BattleResultResponse from(BattleRoom room, List<ParticipantResult> participants) {
        return new BattleResultResponse(room.getId(), room.getProblem().getTitle(), participants);
    }
}
