package com.back.domain.problem.problem.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.back.domain.problem.problem.dto.AdminProblemBulkImportResponse;
import com.back.domain.problem.problem.dto.AdminProblemBulkRequest;
import com.back.domain.problem.problem.dto.AdminProblemBulkValidateResponse;
import com.back.domain.problem.problem.dto.AdminProblemMutationResponse;
import com.back.domain.problem.problem.dto.AdminProblemSingleValidateResponse;
import com.back.domain.problem.problem.dto.AdminProblemUpsertRequest;
import com.back.domain.problem.problem.service.AdminProblemService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/admin/problems")
@RequiredArgsConstructor
public class AdminProblemController {

    private final AdminProblemService adminProblemService;

    @PostMapping
    public ResponseEntity<AdminProblemMutationResponse> create(@Valid @RequestBody AdminProblemUpsertRequest request) {
        AdminProblemMutationResponse response = adminProblemService.createProblem(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PatchMapping("/{problemId}")
    public AdminProblemMutationResponse update(
            @PathVariable("problemId") Long problemId, @Valid @RequestBody AdminProblemUpsertRequest request) {
        return adminProblemService.updateProblem(problemId, request);
    }

    @PostMapping("/bulk/validate")
    public AdminProblemBulkValidateResponse validate(@Valid @RequestBody AdminProblemBulkRequest request) {
        return adminProblemService.validateBulk(request);
    }

    @PostMapping("/validate")
    public AdminProblemSingleValidateResponse validateSingle(@Valid @RequestBody AdminProblemUpsertRequest request) {
        return adminProblemService.validateSingle(request);
    }

    @PostMapping("/bulk/import")
    public ResponseEntity<?> importBulk(@Valid @RequestBody AdminProblemBulkRequest request) {
        AdminProblemBulkValidateResponse validateResponse = adminProblemService.validateBulk(request);
        if (!validateResponse.errors().isEmpty()) {
            return ResponseEntity.badRequest().body(validateResponse);
        }

        AdminProblemBulkImportResponse importResponse = adminProblemService.importBulk(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(importResponse);
    }
}
