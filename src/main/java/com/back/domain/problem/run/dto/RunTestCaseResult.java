package com.back.domain.problem.run.dto;

public record RunTestCaseResult(
        String input, String expectedOutput, String actualOutput, String status, String stderr) {}
