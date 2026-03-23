package com.back.domain.problem.pick.dto;

import java.util.List;

import com.back.domain.problem.problem.enums.DifficultyLevel;

/**
 * 매칭 조건으로 문제를 출제(선정)할 때 사용하는 입력값.
 */
public record ProblemPickRequest(
        String category, DifficultyLevel difficulty, List<Long> participantIds, List<Long> excludeProblemIds) {

    public ProblemPickRequest {
        // 입력값을 방어적으로 복사해 외부에서 리스트를 변경해도 요청 스냅샷이 변하지 않게 한다.
        participantIds = participantIds == null ? List.of() : List.copyOf(participantIds);
        excludeProblemIds = excludeProblemIds == null ? List.of() : List.copyOf(excludeProblemIds);
    }
}
