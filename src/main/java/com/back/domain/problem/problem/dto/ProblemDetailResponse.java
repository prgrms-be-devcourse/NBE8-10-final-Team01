package com.back.domain.problem.problem.dto;

import com.back.domain.problem.problem.entity.Problem;

public record ProblemDetailResponse(
        Long problemId,
        String title,
        String difficulty,
        String content,
        String inputFormat,
        String outputFormat,
        Long timeLimitMs,
        Long memoryLimitMb) {

    public static ProblemDetailResponse from(Problem problem) {
        return new ProblemDetailResponse(
                problem.getId(),
                problem.getTitle(),
                problem.getDifficulty().name(),
                problem.getContent(),
                problem.getInputFormat(),
                problem.getOutputFormat(),
                problem.getTimeLimitMs(),
                problem.getMemoryLimitMb());
    }
}
