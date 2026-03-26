package com.back.global.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import com.back.domain.battle.battleparticipant.entity.BattleParticipant;
import com.back.domain.battle.battleparticipant.entity.BattleParticipantStatus;
import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.battle.battleroom.repository.BattleRoomRepository;
import com.back.domain.battle.result.service.BattleResultService;
import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.repository.MemberRepository;
import com.back.domain.problem.submission.entity.Submission;
import com.back.domain.problem.submission.entity.SubmissionResult;
import com.back.domain.problem.submission.repository.SubmissionRepository;
import com.back.domain.problem.testcase.entity.TestCase;
import com.back.global.judge.dto.Judge0SubmitResponse;
import com.back.global.judge.dto.Judge0SubmitResponse.Status;
import com.back.global.judge.event.JudgeRequestedEvent;

class JudgeServiceTest {

    private final Judge0Client judge0Client = mock(Judge0Client.class);
    private final SubmissionRepository submissionRepository = mock(SubmissionRepository.class);
    private final BattleParticipantRepository battleParticipantRepository = mock(BattleParticipantRepository.class);
    private final BattleRoomRepository battleRoomRepository = mock(BattleRoomRepository.class);
    private final MemberRepository memberRepository = mock(MemberRepository.class);
    private final BattleResultService battleResultService = mock(BattleResultService.class);
    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);

    private final JudgeService judgeService = new JudgeService(
            judge0Client,
            submissionRepository,
            battleParticipantRepository,
            battleRoomRepository,
            memberRepository,
            battleResultService,
            messagingTemplate);

    private static final Long SUBMISSION_ID = 1L;
    private static final Long ROOM_ID = 10L;
    private static final Long MEMBER_ID = 100L;

    private Submission submission;
    private BattleRoom room;
    private Member member;
    private BattleParticipant participant;

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        submission = Submission.create(mock(BattleRoom.class), mock(Member.class), "code", "python");
        room = mock(BattleRoom.class);
        member = mock(Member.class);
        participant = mock(BattleParticipant.class);

        when(submissionRepository.findById(SUBMISSION_ID)).thenReturn(Optional.of(submission));
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
        when(judge0Client.submitBatch(any())).thenReturn(List.of("token"));
        when(judge0Client.getBatchResults(any())).thenReturn(List.of(responses));
    }

    /** AC 흐름에서 필요한 handleAc() 관련 Mock 설정 */
    private void stubHandleAc(List<BattleParticipant> allParticipants) {
        when(battleRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(memberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(member));
        when(battleParticipantRepository.findByBattleRoomAndMember(room, member))
                .thenReturn(Optional.of(participant));
        when(battleParticipantRepository.findByBattleRoom(room)).thenReturn(allParticipants);
    }

    private JudgeRequestedEvent event(List<TestCase> testCases) {
        return new JudgeRequestedEvent(SUBMISSION_ID, ROOM_ID, MEMBER_ID, "code", "python", testCases);
    }

    // ── 채점 결과 저장 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("전체 통과 시 submission result가 AC로 저장된다")
    void judge_allPass_savedAsAc() {
        TestCase tc = mockTestCase("1 2", "3");
        stubJudge0(response(3, "3\n"));

        BattleParticipant exitParticipant = mock(BattleParticipant.class);
        when(exitParticipant.getStatus()).thenReturn(BattleParticipantStatus.EXIT);
        stubHandleAc(List.of(exitParticipant));

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
    @DisplayName("Judge0 폴링 타임아웃 시 submission result가 RE로 저장된다")
    void judge_judge0Timeout_savedAsRe() {
        TestCase tc = mockTestCase("1 2", "3");
        when(judge0Client.submitBatch(any())).thenReturn(List.of("token"));
        when(judge0Client.getBatchResults(any())).thenReturn(List.of(response(1, null))); // 미완료 상태

        judgeService.onJudgeRequested(event(List.of(tc)));

        assertThat(submission.getResult()).isEqualTo(SubmissionResult.RE);
        verify(submissionRepository).save(submission);
    }

    // ── AC 처리 (handleAc) ────────────────────────────────────────────────────

    @Test
    @DisplayName("AC이면 participant가 complete() 처리되고 저장된다")
    void judge_ac_participantCompleted() {
        TestCase tc = mockTestCase("1 2", "3");
        stubJudge0(response(3, "3\n"));

        BattleParticipant exitParticipant = mock(BattleParticipant.class);
        when(exitParticipant.getStatus()).thenReturn(BattleParticipantStatus.EXIT);
        stubHandleAc(List.of(exitParticipant));

        judgeService.onJudgeRequested(event(List.of(tc)));

        verify(participant).complete(any());
        verify(battleParticipantRepository).save(participant);
    }

    @Test
    @DisplayName("AC가 아니면 participant 상태 변경이 없다")
    void judge_notAc_participantNotChanged() {
        TestCase tc = mockTestCase("1 2", "3");
        stubJudge0(response(4, "5\n")); // WA

        judgeService.onJudgeRequested(event(List.of(tc)));

        verify(battleParticipantRepository, never()).save(any());
        verify(battleResultService, never()).settle(any());
    }

    @Test
    @DisplayName("AC이고 전원 완료 시 배틀 정산이 호출된다")
    void judge_ac_allFinished_settlesCalled() {
        TestCase tc = mockTestCase("1 2", "3");
        stubJudge0(response(3, "3\n"));

        BattleParticipant p1 = mock(BattleParticipant.class);
        BattleParticipant p2 = mock(BattleParticipant.class);
        when(p1.getStatus()).thenReturn(BattleParticipantStatus.EXIT);
        when(p2.getStatus()).thenReturn(BattleParticipantStatus.EXIT);
        stubHandleAc(List.of(p1, p2));

        judgeService.onJudgeRequested(event(List.of(tc)));

        verify(battleResultService).settle(ROOM_ID);
    }

    @Test
    @DisplayName("AC이지만 아직 남은 참여자가 있으면 배틀 정산이 호출되지 않는다")
    void judge_ac_notAllFinished_settlesNotCalled() {
        TestCase tc = mockTestCase("1 2", "3");
        stubJudge0(response(3, "3\n"));

        BattleParticipant p1 = mock(BattleParticipant.class);
        BattleParticipant p2 = mock(BattleParticipant.class);
        when(p1.getStatus()).thenReturn(BattleParticipantStatus.EXIT);
        when(p2.getStatus()).thenReturn(BattleParticipantStatus.PLAYING); // 아직 풀이 중
        stubHandleAc(List.of(p1, p2));

        judgeService.onJudgeRequested(event(List.of(tc)));

        verify(battleResultService, never()).settle(any());
    }

    // ── WebSocket 브로드캐스트 ─────────────────────────────────────────────────

    @Test
    @DisplayName("채점 완료 후 SUBMISSION 타입으로 WebSocket 브로드캐스트된다")
    void judge_broadcastsSubmissionResult() {
        TestCase tc = mockTestCase("1 2", "3");
        stubJudge0(response(4, "5\n")); // WA

        judgeService.onJudgeRequested(event(List.of(tc)));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/room/" + ROOM_ID), captor.capture());

        Map<String, Object> message = captor.getValue();
        assertThat(message.get("type")).isEqualTo("SUBMISSION");
        assertThat(message.get("userId")).isEqualTo(MEMBER_ID);
        assertThat(message.get("result")).isEqualTo("WA");
        assertThat(message.get("passedCount")).isEqualTo(0);
        assertThat(message.get("totalCount")).isEqualTo(1);
    }
}
