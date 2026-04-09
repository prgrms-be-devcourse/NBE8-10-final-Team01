package com.back.global.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.problem.submission.entity.Submission;
import com.back.domain.problem.submission.entity.SubmissionResult;
import com.back.domain.problem.submission.repository.SubmissionRepository;
import com.back.domain.problem.testcase.entity.TestCase;
import com.back.global.judge.dto.Judge0SubmitResponse;
import com.back.global.judge.dto.Judge0SubmitResponse.Status;
import com.back.global.judge.event.JudgeRequestedEvent;
import com.back.global.websocket.pubsub.WebSocketMessagePublisher;

class JudgeServiceTest {

    private static final Long SUBMISSION_ID = 1L;
    private static final Long ROOM_ID = 10L;
    private static final Long MEMBER_ID = 100L;

    private final Judge0ExecutionService judge0ExecutionService = mock(Judge0ExecutionService.class);
    private final SubmissionRepository submissionRepository = mock(SubmissionRepository.class);
    private final BattleAcService battleAcService = mock(BattleAcService.class);
    private final WebSocketMessagePublisher publisher = mock(WebSocketMessagePublisher.class);

    private final JudgeService judgeService =
            new JudgeService(judge0ExecutionService, submissionRepository, battleAcService, publisher);

    private Submission submission;

    @BeforeEach
    void setUp() {
        submission = Submission.create(
                mock(BattleRoom.class), mock(com.back.domain.member.member.entity.Member.class), "code", "python");
        when(submissionRepository.findById(SUBMISSION_ID)).thenReturn(Optional.of(submission));
        when(judge0ExecutionService.getLanguageId("python")).thenReturn(71);
    }

    private TestCase mockTestCase(String input, String expectedOutput) {
        TestCase tc = mock(TestCase.class);
        when(tc.getInput()).thenReturn(input);
        when(tc.getExpectedOutput()).thenReturn(expectedOutput);
        return tc;
    }

    private Judge0SubmitResponse response(int statusId, String stdout) {
        return new Judge0SubmitResponse("token", new Status(statusId, ""), stdout, null, null, null);
    }

    private void stubJudge0(Judge0SubmitResponse... responses) {
        when(judge0ExecutionService.execute(any())).thenReturn(List.of(responses));
    }

    private JudgeRequestedEvent event(List<TestCase> testCases) {
        return new JudgeRequestedEvent(SUBMISSION_ID, ROOM_ID, MEMBER_ID, "code", "python", testCases);
    }

    @Test
    @DisplayName("전체 통과 시 submission result가 AC로 저장된다")
    void judge_allPass_savedAsAc() {
        TestCase tc = mockTestCase("1 2", "3");
        stubJudge0(response(3, "3\n"));

        judgeService.onJudgeRequested(event(List.of(tc)));

        assertThat(submission.getResult()).isEqualTo(SubmissionResult.AC);
        assertThat(submission.getPassedCount()).isEqualTo(1);
        assertThat(submission.getTotalCount()).isEqualTo(1);
        verify(submissionRepository).save(submission);
    }

    @Test
    @DisplayName("오답이면 submission result가 WA로 저장된다")
    void judge_wrongAnswer_savedAsWa() {
        TestCase tc = mockTestCase("1 2", "3");
        stubJudge0(response(4, "5\n"));

        judgeService.onJudgeRequested(event(List.of(tc)));

        assertThat(submission.getResult()).isEqualTo(SubmissionResult.WA);
        assertThat(submission.getPassedCount()).isEqualTo(0);
        verify(submissionRepository).save(submission);
    }

    @Test
    @DisplayName("컴파일 에러면 submission result가 CE로 저장된다")
    void judge_compileError_savedAsCe() {
        TestCase tc = mockTestCase("1 2", "3");
        stubJudge0(response(6, null));

        judgeService.onJudgeRequested(event(List.of(tc)));

        assertThat(submission.getResult()).isEqualTo(SubmissionResult.CE);
        verify(submissionRepository).save(submission);
    }

    @Test
    @DisplayName("시간 초과면 submission result가 TLE로 저장된다")
    void judge_timeLimitExceeded_savedAsTle() {
        TestCase tc = mockTestCase("1 2", "3");
        stubJudge0(response(5, null));

        judgeService.onJudgeRequested(event(List.of(tc)));

        assertThat(submission.getResult()).isEqualTo(SubmissionResult.TLE);
        verify(submissionRepository).save(submission);
    }

    @Test
    @DisplayName("런타임 에러면 submission result가 RE로 저장된다")
    void judge_runtimeError_savedAsRe() {
        TestCase tc = mockTestCase("1 2", "3");
        stubJudge0(response(11, null));

        judgeService.onJudgeRequested(event(List.of(tc)));

        assertThat(submission.getResult()).isEqualTo(SubmissionResult.RE);
        verify(submissionRepository).save(submission);
    }

    @Test
    @DisplayName("테스트케이스가 없으면 submission result가 WA로 저장된다")
    void judge_noTestCases_savedAsWa() {
        judgeService.onJudgeRequested(event(List.of()));

        assertThat(submission.getResult()).isEqualTo(SubmissionResult.WA);
        verify(submissionRepository).save(submission);
    }

    @Test
    @DisplayName("Judge0 응답이 비어있으면 submission result가 JUDGE_ERROR로 저장된다")
    void judge_judge0Timeout_savedAsJudgeError() {
        TestCase tc = mockTestCase("1 2", "3");
        when(judge0ExecutionService.execute(any())).thenReturn(List.of());

        judgeService.onJudgeRequested(event(List.of(tc)));

        assertThat(submission.getResult()).isEqualTo(SubmissionResult.JUDGE_ERROR);
        verify(submissionRepository).save(submission);
    }

    @Test
    @DisplayName("AC가 아니면 AC 후속 처리 서비스를 호출하지 않는다")
    void judge_notAc_doesNotCallBattleAcService() {
        TestCase tc = mockTestCase("1 2", "3");
        stubJudge0(response(4, "5\n"));

        judgeService.onJudgeRequested(event(List.of(tc)));

        verify(battleAcService, never()).handleAc(any(), any());
    }

    @Test
    @DisplayName("AC면 AC 후속 처리 서비스를 호출한다")
    void judge_ac_callsBattleAcService() {
        TestCase tc = mockTestCase("1 2", "3");
        stubJudge0(response(3, "3\n"));

        judgeService.onJudgeRequested(event(List.of(tc)));

        verify(battleAcService).handleAc(ROOM_ID, MEMBER_ID);
    }

    @Test
    @DisplayName("채점 완료 후 SUBMISSION 타입으로 WebSocket 브로드캐스트한다")
    void judge_broadcastsSubmissionResult() {
        TestCase tc = mockTestCase("1 2", "3");
        stubJudge0(response(4, "5\n"));

        judgeService.onJudgeRequested(event(List.of(tc)));

        ArgumentCaptor<java.util.Map<String, Object>> captor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(publisher).publish(eq("/topic/room/" + ROOM_ID), captor.capture());

        java.util.Map<String, Object> message = captor.getValue();
        assertThat(message.get("type")).isEqualTo("SUBMISSION");
        assertThat(message.get("userId")).isEqualTo(MEMBER_ID);
        assertThat(message.get("result")).isEqualTo("WA");
        assertThat(message.get("passedCount")).isEqualTo(0);
        assertThat(message.get("totalCount")).isEqualTo(1);
    }
}
