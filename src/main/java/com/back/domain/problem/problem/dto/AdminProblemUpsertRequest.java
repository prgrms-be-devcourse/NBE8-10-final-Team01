package com.back.domain.problem.problem.dto;

import java.util.List;

import com.back.domain.problem.problem.enums.DifficultyLevel;
import com.back.domain.problem.problem.enums.InputMode;
import com.back.domain.problem.problem.enums.JudgeType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AdminProblemUpsertRequest(
        @Size(max = 50, message = "sourceProblemId는 50자 이하여야 합니다.") String sourceProblemId,

        @NotBlank(message = "title은 필수입니다.") String title,
        @NotNull(message = "difficulty는 필수입니다.") DifficultyLevel difficulty,
        @NotBlank(message = "content는 필수입니다.") String content,

        @NotNull(message = "difficultyRating은 필수입니다.") @Min(value = 800, message = "difficultyRating은 800 이상이어야 합니다.") @Max(value = 3500, message = "difficultyRating은 3500 이하여야 합니다.") Integer difficultyRating,

        @NotNull(message = "timeLimitMs는 필수입니다.") @Positive(message = "timeLimitMs는 양수여야 합니다.") Long timeLimitMs,

        @NotNull(message = "memoryLimitMb는 필수입니다.") @Positive(message = "memoryLimitMb는 양수여야 합니다.") Long memoryLimitMb,

        String inputFormat,
        String outputFormat,
        InputMode inputMode,
        JudgeType judgeType,
        String checkerCode,
        List<String> tags,

        List<@Valid StarterCodeRequest> starterCodes,

        @NotNull(message = "sampleCases는 필수입니다.") @Size(min = 3, message = "sampleCases는 최소 3개 이상이어야 합니다.") List<@Valid TestCaseRequest> sampleCases,

        @NotNull(message = "hiddenCases는 필수입니다.") @Size(min = 10, message = "hiddenCases는 최소 10개 이상이어야 합니다.") List<@Valid TestCaseRequest> hiddenCases) {

    public record StarterCodeRequest(
            @NotBlank(message = "language는 필수입니다.") String language,
            @NotBlank(message = "code는 필수입니다.") String code,
            @NotNull(message = "isDefault는 필수입니다.") Boolean isDefault) {}

    public record TestCaseRequest(
            @NotNull(message = "input은 필수입니다.") String input,
            @NotNull(message = "output은 필수입니다.") String output) {}
}
