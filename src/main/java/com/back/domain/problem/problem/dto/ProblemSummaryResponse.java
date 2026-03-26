package com.back.domain.problem.problem.dto;

import com.back.domain.problem.problem.entity.Problem;

public record ProblemSummaryResponse(
        Long problemId,
        String title,
        String difficulty,
        Integer difficultyRating,
        Long timeLimitMs,
        Long memoryLimitMb) {

    public static ProblemSummaryResponse from(Problem problem) {
        return new ProblemSummaryResponse(
                problem.getId(),
                problem.getTitle(),
                problem.getDifficulty().name(),
                problem.getDifficultyRating(),
                problem.getTimeLimitMs(),
                problem.getMemoryLimitMb());
    }
}
