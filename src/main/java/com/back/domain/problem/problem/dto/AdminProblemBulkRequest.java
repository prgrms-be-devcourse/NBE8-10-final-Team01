package com.back.domain.problem.problem.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

public record AdminProblemBulkRequest(
        @NotEmpty(message = "problems는 최소 1개 이상이어야 합니다.") List<@Valid AdminProblemUpsertRequest> problems,
        String validationToken) {}
