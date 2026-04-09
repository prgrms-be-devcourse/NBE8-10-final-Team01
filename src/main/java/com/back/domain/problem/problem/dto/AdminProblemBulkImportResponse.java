package com.back.domain.problem.problem.dto;

import java.util.List;

public record AdminProblemBulkImportResponse(int total, int inserted, int updated, List<Long> problemIds) {}
