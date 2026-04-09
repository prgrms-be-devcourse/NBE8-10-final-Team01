package com.back.domain.problem.problem.dto;

import java.util.List;

public record AdminProblemBulkValidateResponse(
        int total, int validCount, List<ValidationError> errors, String validationToken) {
    public AdminProblemBulkValidateResponse(int total, int validCount, List<ValidationError> errors) {
        this(total, validCount, errors, null);
    }

    public record ValidationError(int index, String field, String message) {}
}
