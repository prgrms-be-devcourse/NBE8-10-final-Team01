package com.back.domain.problem.solo.run.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.problem.problem.repository.ProblemRepository;
import com.back.domain.problem.solo.run.dto.SoloRunRequest;
import com.back.domain.problem.testcase.entity.TestCase;
import com.back.global.judge.event.SoloRunRequestedEvent;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SoloRunService {

    private final ProblemRepository problemRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public Map<String, String> run(SoloRunRequest request, Long memberId) {

        Problem problem = problemRepository
                .findById(request.problemId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 문제입니다."));

        // 샘플 테스트케이스만 필터링 (isSample=true)
        List<TestCase> sampleTestCases = new ArrayList<>(problem.getTestCases())
                .stream().filter(tc -> Boolean.TRUE.equals(tc.getIsSample())).toList();

        eventPublisher.publishEvent(
                new SoloRunRequestedEvent(memberId, request.code(), request.language(), sampleTestCases));

        return Map.of("message", "RUNNING");
    }
}
