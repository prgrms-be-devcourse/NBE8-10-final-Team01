package com.back.domain.problem.solo.submission.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.repository.MemberRepository;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.problem.problem.repository.ProblemRepository;
import com.back.domain.problem.solo.submission.dto.SoloSubmitRequest;
import com.back.domain.problem.solo.submission.entity.SoloSubmission;
import com.back.domain.problem.solo.submission.repository.SoloSubmissionRepository;
import com.back.domain.problem.submission.dto.SubmissionResponse;
import com.back.domain.problem.testcase.entity.TestCase;
import com.back.global.judge.event.SoloJudgeRequestedEvent;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SoloSubmissionService {

    private final ProblemRepository problemRepository;
    private final MemberRepository memberRepository;
    private final SoloSubmissionRepository soloSubmissionRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public SubmissionResponse submit(SoloSubmitRequest request, Long memberId) {

        Problem problem = problemRepository
                .findById(request.problemId())
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 문제입니다."));

        Member member =
                memberRepository.findById(memberId).orElseThrow(() -> new IllegalArgumentException("존재하지 않는 회원입니다."));

        SoloSubmission submission = SoloSubmission.create(member, problem, request.code(), request.language());
        soloSubmissionRepository.save(submission);

        // 전체 테스트케이스 로드 (숨김 포함)
        List<TestCase> testCases = new ArrayList<>(problem.getTestCases());

        eventPublisher.publishEvent(new SoloJudgeRequestedEvent(
                submission.getId(), memberId, request.code(), request.language(), testCases));

        return new SubmissionResponse(submission.getId(), "JUDGING", 0, 0);
    }
}
