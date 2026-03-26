package com.back.domain.problem.problem.dto;

import java.util.List;

import com.back.domain.problem.problem.entity.Problem;

public record ProblemDetailResponse(
        Long problemId,
        String title,
        String difficulty,
        String content,
        String inputFormat,
        String outputFormat,
        Long timeLimitMs,
        Long memoryLimitMb,
        List<String> supportedLanguages,
        String defaultLanguage,
        List<StarterCode> starterCodes,
        List<SampleCase> sampleCases) {

    public record StarterCode(String language, String code) {}

    public record SampleCase(String input, String output) {}

    public static ProblemDetailResponse from(
            Problem problem,
            List<String> supportedLanguages,
            String defaultLanguage,
            List<StarterCode> starterCodes,
            List<SampleCase> sampleCases) {
        return new ProblemDetailResponse(
                problem.getId(),
                problem.getTitle(),
                problem.getDifficulty().name(),
                problem.getContent(),
                problem.getInputFormat(),
                problem.getOutputFormat(),
                problem.getTimeLimitMs(),
                problem.getMemoryLimitMb(),
                supportedLanguages,
                defaultLanguage,
                starterCodes,
                sampleCases);
    }
}
