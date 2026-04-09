package com.back.domain.problem.problem.dto;

import java.util.List;

public record AdminProblemSingleValidateResponse(boolean valid, List<ValidationError> errors) {
    public record ValidationError(String field, String message) {}
}
