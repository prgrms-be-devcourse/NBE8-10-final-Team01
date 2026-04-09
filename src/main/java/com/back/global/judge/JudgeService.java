package com.back.global.judge;

import java.util.List;
import java.util.Map;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.back.domain.problem.submission.entity.Submission;
import com.back.domain.problem.submission.entity.SubmissionResult;
import com.back.domain.problem.submission.repository.SubmissionRepository;
import com.back.domain.problem.testcase.entity.TestCase;
import com.back.global.judge.dto.Judge0SubmitRequest;
import com.back.global.judge.dto.Judge0SubmitResponse;
import com.back.global.judge.event.JudgeRequestedEvent;
import com.back.global.websocket.pubsub.WebSocketMessagePublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class JudgeService {

    private final Judge0ExecutionService judge0ExecutionService;
    private final SubmissionRepository submissionRepository;
    private final BattleAcService battleAcService;
    private final WebSocketMessagePublisher publisher;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onJudgeRequested(JudgeRequestedEvent event) {
        judge(
                event.submissionId(),
                event.roomId(),
                event.memberId(),
                event.code(),
                event.language(),
                event.testCases());
    }

    private void judge(
            Long submissionId, Long roomId, Long memberId, String code, String language, List<TestCase> testCases) {

        int totalCount = testCases.size();
        SubmissionResult judgeResult;
        int passedCount = 0;

        if (totalCount == 0) {
            log.warn("Submission {} has no test cases, marking as WA", submissionId);
            judgeResult = SubmissionResult.WA;
        } else { // 테스트 케이스가 1개 이상의 경우
            int languageId = judge0ExecutionService.getLanguageId(language);
            List<Judge0SubmitRequest> batchRequests = testCases.stream()
                    .map(tc -> {
                        String input = judge0ExecutionService.restoreEscapedNewline(tc.getInput());
                        String expectedOutput = judge0ExecutionService.restoreEscapedNewline(tc.getExpectedOutput());
                        return new Judge0SubmitRequest(code, languageId, input != null ? input : "", expectedOutput);
                    })
                    .toList();
            List<Judge0SubmitResponse> results = judge0ExecutionService.execute(batchRequests);
            results.forEach(r -> log.info(
                    "Judge0 result: token={}, statusId={}, stderr={}, compileOutput={}, message={}",
                    r.token(),
                    r.status() != null ? r.status().id() : null,
                    r.stderr(),
                    r.compileOutput(),
                    r.message()));
            judgeResult = aggregateResult(results, totalCount);
            passedCount = (int) results.stream()
                    .filter(r -> r.status() != null && r.status().id() == 3)
                    .count();
        }

        Submission submission = submissionRepository
                .findById(submissionId)
                .orElseThrow(() -> new IllegalStateException("Submission not found: " + submissionId));
        submission.applyJudgeResult(judgeResult, passedCount, totalCount);
        submissionRepository.save(submission);

        publisher.publish(
                "/topic/room/" + roomId,
                Map.of(
                        "type", "SUBMISSION",
                        "userId", memberId,
                        "result", judgeResult.name(),
                        "passedCount", passedCount,
                        "totalCount", totalCount));

        if (judgeResult == SubmissionResult.AC) {
            battleAcService.handleAc(roomId, memberId);
        }
    }

    private SubmissionResult aggregateResult(List<Judge0SubmitResponse> results, int totalCount) {
        if (results.isEmpty()) return SubmissionResult.JUDGE_ERROR;

        if (results.stream().anyMatch(r -> r.status() != null && r.status().id() == 6)) {
            return SubmissionResult.CE;
        }
        if (results.stream().anyMatch(r -> r.status() != null && r.status().id() >= 7)) {
            return SubmissionResult.RE;
        }
        if (results.stream().anyMatch(r -> r.status() != null && r.status().id() == 5)) {
            return SubmissionResult.TLE;
        }
        long passed = results.stream()
                .filter(r -> r.status() != null && r.status().id() == 3)
                .count();
        if (passed == totalCount) return SubmissionResult.AC;

        return SubmissionResult.WA;
    }
}
