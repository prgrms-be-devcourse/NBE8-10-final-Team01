package com.back.global.judge;

import java.util.List;
import java.util.Map;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.back.domain.problem.run.dto.RunTestCaseResult;
import com.back.domain.problem.testcase.entity.TestCase;
import com.back.global.judge.dto.Judge0SubmitRequest;
import com.back.global.judge.dto.Judge0SubmitResponse;
import com.back.global.judge.event.RunRequestedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RunJudgeService {

    private static final int MAX_POLL_ATTEMPTS = 10;
    private static final int POLL_INTERVAL_MS = 1000;

    private final Judge0Client judge0Client;
    private final SimpMessagingTemplate messagingTemplate;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRunRequested(RunRequestedEvent event) {
        run(event.roomId(), event.memberId(), event.code(), event.language(), event.testCases());
    }

    private void run(Long roomId, Long memberId, String code, String language, List<TestCase> testCases) {

        List<RunTestCaseResult> results;

        if (testCases.isEmpty()) {
            log.warn("Run request for room {} has no sample test cases", roomId);
            results = List.of();
        } else {
            int languageId = getLanguageId(language);

            // TODO: 함수형 코드 지원 시 driverCode 합치기
            // String fullCode = code + "\n" + problem.getDriverCode().get(language);
            List<Judge0SubmitRequest> batchRequests = testCases.stream()
                    .map(tc -> new Judge0SubmitRequest(
                            code, languageId, tc.getInput() != null ? tc.getInput() : "", tc.getExpectedOutput()))
                    .toList();

            List<Judge0SubmitResponse> judgeResponses = runJudge(batchRequests);

            results = buildResults(testCases, judgeResponses);
        }

        // WebSocket: 케이스별 실행 결과 push
        messagingTemplate.convertAndSend(
                "/topic/room/" + roomId + "/run",
                Map.of(
                        "type", "RUN_RESULT",
                        "userId", memberId,
                        "results", results));
    }

    private List<RunTestCaseResult> buildResults(List<TestCase> testCases, List<Judge0SubmitResponse> judgeResponses) {

        // 폴링 타임아웃 등으로 결과가 없을 경우 전체 RE 처리
        if (judgeResponses.isEmpty()) {
            return testCases.stream()
                    .map(tc ->
                            new RunTestCaseResult(tc.getInput(), tc.getExpectedOutput(), null, "RE", "실행 시간이 초과되었습니다."))
                    .toList();
        }

        return java.util.stream.IntStream.range(0, testCases.size())
                .mapToObj(i -> {
                    TestCase tc = testCases.get(i);
                    if (i >= judgeResponses.size()) {
                        return new RunTestCaseResult(tc.getInput(), tc.getExpectedOutput(), null, "RE", null);
                    }
                    Judge0SubmitResponse r = judgeResponses.get(i);
                    String status = resolveStatus(r);
                    String actualOutput = r.stdout() != null ? r.stdout().stripTrailing() : null;
                    String stderr = r.stderr() != null ? r.stderr() : r.compileOutput();
                    return new RunTestCaseResult(tc.getInput(), tc.getExpectedOutput(), actualOutput, status, stderr);
                })
                .toList();
    }

    private String resolveStatus(Judge0SubmitResponse r) {
        if (r.status() == null) return "RE";
        return switch (r.status().id()) {
            case 3 -> "AC";
            case 4 -> "WA";
            case 5 -> "TLE";
            case 6 -> "CE";
            default -> "RE";
        };
    }

    private List<Judge0SubmitResponse> runJudge(List<Judge0SubmitRequest> batchRequests) {
        try {
            List<String> tokens = judge0Client.submitBatch(batchRequests);
            for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
                List<Judge0SubmitResponse> results = judge0Client.getBatchResults(tokens);
                if (results.stream().allMatch(Judge0SubmitResponse::isCompleted)) {
                    return results;
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
            log.warn("Judge0 run polling timed out after {} attempts", MAX_POLL_ATTEMPTS);
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Judge0 run polling interrupted", e);
            return List.of();
        } catch (Exception e) {
            log.error("Judge0 run failed", e);
            return List.of();
        }
    }

    private int getLanguageId(String language) {
        return switch (language.toLowerCase()) {
            case "python", "python3" -> 71;
            case "java" -> 62;
            case "cpp", "c++" -> 54;
            case "c" -> 50;
            case "javascript", "js" -> 63;
            default -> throw new IllegalArgumentException("지원하지 않는 언어: " + language);
        };
    }
}
