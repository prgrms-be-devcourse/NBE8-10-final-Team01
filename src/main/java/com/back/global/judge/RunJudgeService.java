package com.back.global.judge;

import java.util.List;
import java.util.Map;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.back.domain.problem.run.dto.RunTestCaseResult;
import com.back.domain.problem.testcase.entity.TestCase;
import com.back.global.judge.dto.Judge0SubmitRequest;
import com.back.global.judge.dto.Judge0SubmitResponse;
import com.back.global.judge.event.RunRequestedEvent;
import com.back.global.websocket.pubsub.WebSocketMessagePublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RunJudgeService {

    private final Judge0ExecutionService judge0ExecutionService;
    private final WebSocketMessagePublisher publisher;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRunRequested(RunRequestedEvent event) {
        run(
                event.roomId(),
                event.memberId(),
                event.code(),
                event.language(),
                event.testCases(),
                "/topic/room/" + event.roomId() + "/run");
    }

    void run(Long roomId, Long memberId, String code, String language, List<TestCase> testCases, String topic) {

        List<RunTestCaseResult> results;

        if (testCases.isEmpty()) {
            log.warn("Run request for room {} has no sample test cases", roomId);
            results = List.of();
        } else {
            int languageId = judge0ExecutionService.getLanguageId(language);

            // TODO: 함수형 코드 지원 시 driverCode 합치기
            // String fullCode = code + "\n" + problem.getDriverCode().get(language);
            List<Judge0SubmitRequest> batchRequests = testCases.stream()
                    .map(tc -> {
                        String input = judge0ExecutionService.restoreEscapedNewline(tc.getInput());
                        String expectedOutput = judge0ExecutionService.restoreEscapedNewline(tc.getExpectedOutput());
                        return new Judge0SubmitRequest(code, languageId, input != null ? input : "", expectedOutput);
                    })
                    .toList();

            List<Judge0SubmitResponse> judgeResponses = judge0ExecutionService.execute(batchRequests);
            results = buildResults(testCases, judgeResponses);
        }

        publisher.publish(
                topic,
                Map.of(
                        "type", "RUN_RESULT",
                        "userId", memberId,
                        "results", results));
    }

    public List<RunTestCaseResult> buildResults(List<TestCase> testCases, List<Judge0SubmitResponse> judgeResponses) {

        if (judgeResponses.isEmpty()) {
            return testCases.stream()
                    .map(tc -> new RunTestCaseResult(
                            judge0ExecutionService.restoreEscapedNewline(tc.getInput()),
                            judge0ExecutionService.restoreEscapedNewline(tc.getExpectedOutput()),
                            null,
                            "JUDGE_ERROR",
                            "채점 서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요."))
                    .toList();
        }

        return java.util.stream.IntStream.range(0, testCases.size())
                .mapToObj(i -> {
                    TestCase tc = testCases.get(i);
                    String input = judge0ExecutionService.restoreEscapedNewline(tc.getInput());
                    String expectedOutput = judge0ExecutionService.restoreEscapedNewline(tc.getExpectedOutput());
                    if (i >= judgeResponses.size()) {
                        return new RunTestCaseResult(input, expectedOutput, null, "RE", null);
                    }
                    Judge0SubmitResponse r = judgeResponses.get(i);
                    String status = resolveStatus(r);
                    String actualOutput = r.stdout() != null ? r.stdout().stripTrailing() : null;
                    String stderr = r.stderr() != null ? r.stderr() : r.compileOutput();
                    return new RunTestCaseResult(input, expectedOutput, actualOutput, status, stderr);
                })
                .toList();
    }

    public String resolveStatus(Judge0SubmitResponse r) {
        if (r.status() == null) return "RE";
        return switch (r.status().id()) {
            case 3 -> "AC";
            case 4 -> "WA";
            case 5 -> "TLE";
            case 6 -> "CE";
            default -> "RE";
        };
    }
}
