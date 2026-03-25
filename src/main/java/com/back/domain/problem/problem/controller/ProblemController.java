package com.back.domain.problem.problem.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.back.domain.problem.problem.dto.ProblemDetailResponse;
import com.back.domain.problem.problem.service.ProblemService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/problems")
@RequiredArgsConstructor
public class ProblemController {

    private final ProblemService problemService;

    @GetMapping("/{problemId}")
    public ProblemDetailResponse getProblem(@PathVariable("problemId") Long problemId) {
        return problemService.getProblem(problemId);
    }
}
