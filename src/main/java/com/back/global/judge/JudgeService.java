package com.back.global.judge;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

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
import com.back.global.judge.dto.Judge0SubmitRequest;
import com.back.global.judge.dto.Judge0SubmitResponse;
import com.back.global.judge.event.JudgeRequestedEvent;
import com.back.global.websocket.BattleTimerStore;
import com.back.global.websocket.pubsub.WebSocketMessagePublisher;

import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class JudgeService {

    private final Judge0ExecutionService judge0ExecutionService;
    private final SubmissionRepository submissionRepository;
    private final BattleParticipantRepository battleParticipantRepository;
    private final BattleRoomRepository battleRoomRepository;
    private final MemberRepository memberRepository;
    private final BattleResultService battleResultService;
    private final BattleTimerStore battleTimerStore;
    private final WebSocketMessagePublisher publisher;

    /**
     * 트랜잭션 커밋 후 비동기 채점 실행.
     * @TransactionalEventListener(AFTER_COMMIT) 으로 커밋 확정 후 실행이 보장되므로
     * 이후 findById(submissionId) 호출 시 레코드가 반드시 존재한다.
     */
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

        // 문제에 있는 테스트 케이스의 총 개수
        int totalCount = testCases.size();
        SubmissionResult judgeResult;
        int passedCount = 0;

        // 테스트케이스가 0개의 경우 WA처리
        if (totalCount == 0) {
            log.warn("Submission {} has no test cases, marking as WA", submissionId);
            judgeResult = SubmissionResult.WA;
        } else { // 테스트 케이스가 1개 이상의 경우
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
            List<Judge0SubmitResponse> results = judge0ExecutionService.execute(batchRequests);
            results.forEach(r -> log.info(
                    "Judge0 result: token={}, statusId={}, stderr={}, compileOutput={}, message={}",
                    r.token(),
                    r.status() != null ? r.status().id() : null,
                    r.stderr(),
                    r.compileOutput(),
                    r.message()));
            // 결과 집계
            // 우선순위: CE(6) > RE(7~14) > TLE(5) > WA > AC(3)
            //  passedCount = status.id == 3 인 케이스 수
            judgeResult = aggregateResult(results, totalCount);
            passedCount = (int) results.stream()
                    .filter(r -> r.status() != null && r.status().id() == 3)
                    .count();
        }

        // 결과 저장 — @TransactionalEventListener(AFTER_COMMIT) 으로 커밋 확정 후 실행되므로 안전
        Submission submission = submissionRepository
                .findById(submissionId)
                .orElseThrow(() -> new IllegalStateException("Submission not found: " + submissionId));
        submission.applyJudgeResult(judgeResult, passedCount, totalCount);
        submissionRepository.save(submission);

        // WebSocket: 제출 결과 브로드캐스트 (알림)
        publisher.publish(
                "/topic/room/" + roomId,
                Map.of(
                        "type", "SUBMISSION",
                        "userId", memberId,
                        "result", judgeResult.name(),
                        "passedCount", passedCount,
                        "totalCount", totalCount));

        /*
         * result != AC일 때
         * SUBMISSION 브로드캐스트만 하고 끝. handleAc()가 호출되지 않으므로 participant 상태 변경 없음, 정산도 없음.
         */
        if (judgeResult == SubmissionResult.AC) {
            handleAc(roomId, memberId);
        }
        // SUBMISSION 브로드캐스트는 알림이고, AC일 때만 참여자 완료 처리 → 전원 완료 시 정산까지 연결
    }

    private void handleAc(Long roomId, Long memberId) {
        BattleRoom room = battleRoomRepository
                .findById(roomId)
                .orElseThrow(() -> new IllegalStateException("BattleRoom not found: " + roomId));
        Member member = memberRepository
                .findById(memberId)
                .orElseThrow(() -> new IllegalStateException("Member not found: " + memberId));

        BattleParticipant participant = battleParticipantRepository
                .findByBattleRoomAndMember(room, member)
                .orElseThrow(() -> new IllegalStateException("Participant not found"));

        /*
         * status: PLAYING에서 SOLVED
         * finishTime 기록
         */
        participant.complete(LocalDateTime.now());
        battleParticipantRepository.save(participant);

        List<BattleParticipant> allParticipants = battleParticipantRepository.findByBattleRoom(room);
        long completedCount = allParticipants.stream()
                .filter(p -> p.getStatus() == BattleParticipantStatus.SOLVED)
                .count();
        // PARTICIPANT_DONE 브로드캐스트
        publisher.publish(
                "/topic/room/" + roomId,
                Map.of("type", "PARTICIPANT_DONE", "userId", memberId, "rank", completedCount));

        /*
         * 전원 SOLVED 체크
         *     → 아직 남은 사람 있으면 종료
         *     → 전원 완료면 타이머 취소 후 즉시 settle()
         */
        boolean allFinished = allParticipants.stream().allMatch(p -> p.getStatus() == BattleParticipantStatus.SOLVED);
        if (allFinished) {
            battleTimerStore.cancel(roomId);
            try {
                battleResultService.settle(roomId);
            } catch (OptimisticLockException e) {
                log.info("settle 낙관적 락 충돌 - 이미 정산 완료됨 roomId={}", roomId);
            }
        }
    }

    /**
     * 우선순위: CE > RE > TLE > WA > AC
     */
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
