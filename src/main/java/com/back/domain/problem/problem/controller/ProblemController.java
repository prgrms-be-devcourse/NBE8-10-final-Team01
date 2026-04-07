package com.back.domain.problem.problem.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.back.domain.problem.problem.dto.ProblemDetailResponse;
import com.back.domain.problem.problem.dto.ProblemListResponse;
import com.back.domain.problem.problem.service.ProblemService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/problems")
@RequiredArgsConstructor
public class ProblemController {

    private final ProblemService problemService;

    @GetMapping
    public ProblemListResponse getProblems(
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return problemService.getProblems(page, size);
    }

    @GetMapping("/{problemId}")
    public ProblemDetailResponse getProblem(
            @PathVariable("problemId") Long problemId,
            @RequestParam(value = "lang", required = false) String lang
    ) {
        return problemService.getProblem(problemId, lang);
    }
}
