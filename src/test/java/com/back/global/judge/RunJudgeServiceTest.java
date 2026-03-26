package com.back.global.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.back.domain.problem.run.dto.RunTestCaseResult;
import com.back.domain.problem.testcase.entity.TestCase;
import com.back.global.judge.dto.Judge0SubmitResponse;
import com.back.global.judge.dto.Judge0SubmitResponse.Status;
import com.back.global.judge.event.RunRequestedEvent;

class RunJudgeServiceTest {

    private final Judge0Client judge0Client = mock(Judge0Client.class);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);

    private final RunJudgeService runJudgeService = new RunJudgeService(judge0Client, messagingTemplate);

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private TestCase mockTestCase(String input, String expectedOutput) {
        TestCase tc = mock(TestCase.class);
        when(tc.getInput()).thenReturn(input);
        when(tc.getExpectedOutput()).thenReturn(expectedOutput);
        when(tc.getIsSample()).thenReturn(true);
        return tc;
    }

    private Judge0SubmitResponse response(int statusId, String stdout, String stderr, String compileOutput) {
        return new Judge0SubmitResponse("token", new Status(statusId, ""), stdout, stderr, compileOutput, null);
    }

    @SuppressWarnings("unchecked")
    private List<RunTestCaseResult> captureResults() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(any(String.class), captor.capture());
        return (List<RunTestCaseResult>) captor.getValue().get("results");
    }

    // ── 정상 케이스 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("정답이면 status AC, actualOutput이 stdout으로 채워진다")
    void run_ac() {
        TestCase tc = mockTestCase("1 2", "3");
        when(judge0Client.submitBatch(any())).thenReturn(List.of("token"));
        when(judge0Client.getBatchResults(any())).thenReturn(List.of(response(3, "3\n", null, null)));

        runJudgeService.onRunRequested(new RunRequestedEvent(1L, 1L, "print(3)", "python", List.of(tc)));

        List<RunTestCaseResult> results = captureResults();
        assertThat(results).hasSize(1);
        assertThat(results.get(0).status()).isEqualTo("AC");
        assertThat(results.get(0).actualOutput()).isEqualTo("3");
        assertThat(results.get(0).input()).isEqualTo("1 2");
        assertThat(results.get(0).expectedOutput()).isEqualTo("3");
    }

    @Test
    @DisplayName("출력이 다르면 status WA")
    void run_wa() {
        TestCase tc = mockTestCase("1 2", "3");
        when(judge0Client.submitBatch(any())).thenReturn(List.of("token"));
        when(judge0Client.getBatchResults(any())).thenReturn(List.of(response(4, "5\n", null, null)));

        runJudgeService.onRunRequested(new RunRequestedEvent(1L, 1L, "print(5)", "python", List.of(tc)));

        List<RunTestCaseResult> results = captureResults();
        assertThat(results.get(0).status()).isEqualTo("WA");
        assertThat(results.get(0).actualOutput()).isEqualTo("5");
    }

    @Test
    @DisplayName("컴파일 에러면 status CE, stderr에 compileOutput이 담긴다")
    void run_ce() {
        TestCase tc = mockTestCase("1 2", "3");
        when(judge0Client.submitBatch(any())).thenReturn(List.of("token"));
        when(judge0Client.getBatchResults(any()))
                .thenReturn(List.of(response(6, null, null, "SyntaxError: invalid syntax")));

        runJudgeService.onRunRequested(new RunRequestedEvent(1L, 1L, "prnit(3)", "python", List.of(tc)));

        List<RunTestCaseResult> results = captureResults();
        assertThat(results.get(0).status()).isEqualTo("CE");
        assertThat(results.get(0).stderr()).isEqualTo("SyntaxError: invalid syntax");
    }

    @Test
    @DisplayName("시간 초과면 status TLE")
    void run_tle() {
        TestCase tc = mockTestCase("1 2", "3");
        when(judge0Client.submitBatch(any())).thenReturn(List.of("token"));
        when(judge0Client.getBatchResults(any())).thenReturn(List.of(response(5, null, null, null)));

        runJudgeService.onRunRequested(new RunRequestedEvent(1L, 1L, "while True: pass", "python", List.of(tc)));

        List<RunTestCaseResult> results = captureResults();
        assertThat(results.get(0).status()).isEqualTo("TLE");
    }

    @Test
    @DisplayName("런타임 에러면 status RE, stderr가 채워진다")
    void run_re() {
        TestCase tc = mockTestCase("1 2", "3");
        when(judge0Client.submitBatch(any())).thenReturn(List.of("token"));
        when(judge0Client.getBatchResults(any())).thenReturn(List.of(response(11, null, "ZeroDivisionError", null)));

        runJudgeService.onRunRequested(new RunRequestedEvent(1L, 1L, "print(1/0)", "python", List.of(tc)));

        List<RunTestCaseResult> results = captureResults();
        assertThat(results.get(0).status()).isEqualTo("RE");
        assertThat(results.get(0).stderr()).isEqualTo("ZeroDivisionError");
    }

    @Test
    @DisplayName("여러 케이스 중 일부만 통과해도 케이스별 개별 status를 반환한다")
    void run_mixed_results() {
        TestCase tc1 = mockTestCase("1 2", "3");
        TestCase tc2 = mockTestCase("5 7", "12");
        when(judge0Client.submitBatch(any())).thenReturn(List.of("token1", "token2"));
        when(judge0Client.getBatchResults(any()))
                .thenReturn(List.of(response(3, "3\n", null, null), response(4, "13\n", null, null)));

        runJudgeService.onRunRequested(new RunRequestedEvent(1L, 1L, "code", "python", List.of(tc1, tc2)));

        List<RunTestCaseResult> results = captureResults();
        assertThat(results).hasSize(2);
        assertThat(results.get(0).status()).isEqualTo("AC");
        assertThat(results.get(1).status()).isEqualTo("WA");
        assertThat(results.get(1).actualOutput()).isEqualTo("13");
    }

    // ── 예외 케이스 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("샘플 테스트케이스가 없으면 빈 results를 WebSocket으로 전송한다")
    void run_noTestCases() {
        runJudgeService.onRunRequested(new RunRequestedEvent(1L, 1L, "code", "python", List.of()));

        List<RunTestCaseResult> results = captureResults();
        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("Judge0 폴링 타임아웃 시 전체 케이스를 RE로 반환한다")
    void run_judge0Timeout() {
        TestCase tc = mockTestCase("1 2", "3");
        when(judge0Client.submitBatch(any())).thenReturn(List.of("token"));
        // 계속 미완료 상태 반환 → 타임아웃
        when(judge0Client.getBatchResults(any()))
                .thenReturn(List.of(response(1, null, null, null))); // status 1 = In Queue (미완료)

        runJudgeService.onRunRequested(new RunRequestedEvent(1L, 1L, "code", "python", List.of(tc)));

        List<RunTestCaseResult> results = captureResults();
        assertThat(results.get(0).status()).isEqualTo("RE");
    }

    // ── WebSocket 전송 검증 ───────────────────────────────────────────────────

    @Test
    @DisplayName("결과는 /topic/room/{roomId}/run 토픽으로 전송된다")
    void run_sendsToCorrectTopic() {
        TestCase tc = mockTestCase("1 2", "3");
        when(judge0Client.submitBatch(any())).thenReturn(List.of("token"));
        when(judge0Client.getBatchResults(any())).thenReturn(List.of(response(3, "3\n", null, null)));

        runJudgeService.onRunRequested(new RunRequestedEvent(42L, 1L, "code", "python", List.of(tc)));

        verify(messagingTemplate).convertAndSend(eq("/topic/room/42/run"), any(Object.class));
    }

    @Test
    @DisplayName("WebSocket 메시지 type은 RUN_RESULT, userId가 포함된다")
    void run_messageContainsTypeAndUserId() {
        TestCase tc = mockTestCase("1 2", "3");
        when(judge0Client.submitBatch(any())).thenReturn(List.of("token"));
        when(judge0Client.getBatchResults(any())).thenReturn(List.of(response(3, "3\n", null, null)));

        runJudgeService.onRunRequested(new RunRequestedEvent(1L, 99L, "code", "python", List.of(tc)));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(any(String.class), captor.capture());

        Map<String, Object> message = captor.getValue();
        assertThat(message.get("type")).isEqualTo("RUN_RESULT");
        assertThat(message.get("userId")).isEqualTo(99L);
    }
}
