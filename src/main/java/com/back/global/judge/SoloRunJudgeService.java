package com.back.global.judge;

import java.util.List;
import java.util.Map;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.back.domain.problem.testcase.entity.TestCase;
import com.back.global.judge.dto.Judge0SubmitRequest;
import com.back.global.judge.dto.Judge0SubmitResponse;
import com.back.global.judge.event.SoloRunRequestedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class SoloRunJudgeService {

    private final Judge0ExecutionService judge0ExecutionService;
    private final RunJudgeService runJudgeService; // buildResults, resolveStatus 재사용
    private final SimpMessagingTemplate messagingTemplate;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSoloRunRequested(SoloRunRequestedEvent event) {
        run(event.memberId(), event.code(), event.language(), event.testCases());
    }

    private void run(Long memberId, String code, String language, List<TestCase> testCases) {

        var results = testCases.isEmpty() ? List.of() : buildAndRun(code, language, testCases);

        messagingTemplate.convertAndSend(
                "/topic/solo/" + memberId + "/run",
                Map.of(
                        "type", "RUN_RESULT",
                        "userId", memberId,
                        "results", results));
    }

    private List<?> buildAndRun(String code, String language, List<TestCase> testCases) {
        int languageId = judge0ExecutionService.getLanguageId(language);

        // TODO: 함수형 코드 지원 시 driverCode 합치기
        // String fullCode = code + "\n" + problem.getDriverCode().get(language);
        List<Judge0SubmitRequest> batchRequests = testCases.stream()
                .map(tc -> new Judge0SubmitRequest(
                        code, languageId, tc.getInput() != null ? tc.getInput() : "", tc.getExpectedOutput()))
                .toList();

        List<Judge0SubmitResponse> judgeResponses = judge0ExecutionService.execute(batchRequests);
        return runJudgeService.buildResults(testCases, judgeResponses);
    }
}
