package com.back.global.judge;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.back.domain.problem.solo.submission.entity.SoloSubmission;
import com.back.domain.problem.solo.submission.repository.SoloSubmissionRepository;
import com.back.domain.problem.submission.entity.SubmissionResult;
import com.back.domain.problem.testcase.entity.TestCase;
import com.back.domain.rating.profile.service.RatingProfileService;
import com.back.domain.review.reviewschedule.service.ReviewScheduleService;
import com.back.global.judge.dto.Judge0SubmitRequest;
import com.back.global.judge.dto.Judge0SubmitResponse;
import com.back.global.judge.event.SoloJudgeRequestedEvent;
import com.back.global.websocket.pubsub.WebSocketMessagePublisher;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class SoloJudgeService {

    private final Judge0ExecutionService judge0ExecutionService;
    private final SoloSubmissionRepository soloSubmissionRepository;
    private final RatingProfileService ratingProfileService;
    private final ReviewScheduleService reviewScheduleService;
    private final WebSocketMessagePublisher publisher;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSoloJudgeRequested(SoloJudgeRequestedEvent event) {
        judge(event.soloSubmissionId(), event.memberId(), event.code(), event.language(), event.testCases());
    }

    private void judge(Long soloSubmissionId, Long memberId, String code, String language, List<TestCase> testCases) {

        int totalCount = testCases.size();
        SubmissionResult judgeResult;
        int passedCount = 0;

        if (totalCount == 0) {
            log.warn("SoloSubmission {} has no test cases, marking as WA", soloSubmissionId);
            judgeResult = SubmissionResult.WA;
        } else {
            int languageId = judge0ExecutionService.getLanguageId(language);

            // TODO: 함수형 코드 지원 시 driverCode 합치기
            // String fullCode = code + "\n" + problem.getDriverCode().get(language);
            List<Judge0SubmitRequest> batchRequests = testCases.stream()
                    .map(tc -> new Judge0SubmitRequest(
                            code, languageId, tc.getInput() != null ? tc.getInput() : "", tc.getExpectedOutput()))
                    .toList();

            List<Judge0SubmitResponse> results = judge0ExecutionService.execute(batchRequests);
            judgeResult = aggregateResult(results, totalCount);
            passedCount = (int) results.stream()
                    .filter(r -> r.status() != null && r.status().id() == 3)
                    .count();
        }

        SoloSubmission submission = soloSubmissionRepository
                .findWithMemberAndProblemById(soloSubmissionId)
                .orElseThrow(() -> new IllegalStateException("SoloSubmission not found: " + soloSubmissionId));
        submission.applyJudgeResult(judgeResult, passedCount, totalCount);
        soloSubmissionRepository.save(submission);

        if (judgeResult == SubmissionResult.AC) {
            LocalDateTime now = LocalDateTime.now();
            // 문제별 첫 AC일 때만 솔로 난이도 점수/카운트를 반영한다.
            ratingProfileService.applySoloFirstSolve(submission.getMember(), submission.getProblem(), now);
            reviewScheduleService.recordAcResult(submission.getMember(), submission.getProblem(), now);
        }

        publisher.publish(
                "/topic/solo/" + memberId,
                Map.of(
                        "type", "SUBMISSION",
                        "userId", memberId,
                        "result", judgeResult.name(),
                        "passedCount", passedCount,
                        "totalCount", totalCount));
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
