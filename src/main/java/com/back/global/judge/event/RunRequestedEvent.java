package com.back.global.judge.event;

import java.util.List;

import com.back.domain.problem.testcase.entity.TestCase;

public record RunRequestedEvent(Long roomId, Long memberId, String code, String language, List<TestCase> testCases) {}
