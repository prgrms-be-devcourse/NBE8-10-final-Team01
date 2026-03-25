package com.back.global.judge;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class JudgeService {

    private static final int MAX_POLL_ATTEMPTS = 10;
    private static final int POLL_INTERVAL_MS = 1000;

    private final Judge0Client judge0Client;
    private final SubmissionRepository submissionRepository;
    private final BattleParticipantRepository battleParticipantRepository;
    private final BattleRoomRepository battleRoomRepository;
    private final MemberRepository memberRepository;
    private final BattleResultService battleResultService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 비동기 채점 실행. SubmissionService에서 저장 직후 호출된다.
     * testCases는 SubmissionService 트랜잭션 안에서 미리 로드된 데이터이며,
     * 직접 컬럼 필드(input, expectedOutput)만 사용하므로 detach 후에도 안전하다.
     */
    @Async
    public void judge(
            Long submissionId, Long roomId, Long memberId, String code, String language, List<TestCase> testCases) {

        int totalCount = testCases.size();
        SubmissionResult judgeResult;
        int passedCount = 0;

        if (totalCount == 0) {
            log.warn("Submission {} has no test cases, marking as WA", submissionId);
            judgeResult = SubmissionResult.WA;
        } else {
            int languageId = getLanguageId(language);
            List<Judge0SubmitRequest> batchRequests = testCases.stream()
                    .map(tc -> new Judge0SubmitRequest(
                            code, languageId, tc.getInput() != null ? tc.getInput() : "", tc.getExpectedOutput()))
                    .toList();

            List<Judge0SubmitResponse> results = runJudge(batchRequests);
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

        // 결과 저장 — 폴링 완료 시점에는 SubmissionService 트랜잭션이 반드시 커밋된 상태
        Submission submission = submissionRepository
                .findById(submissionId)
                .orElseThrow(() -> new IllegalStateException("Submission not found: " + submissionId));
        submission.applyJudgeResult(judgeResult, passedCount, totalCount);
        submissionRepository.save(submission);

        // WebSocket: 제출 결과 브로드캐스트 (알림)
        messagingTemplate.convertAndSend(
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
         * status: PLAYING에서 EXIT
         * finishTime 기록
         */
        participant.complete(LocalDateTime.now());
        battleParticipantRepository.save(participant);

        List<BattleParticipant> allParticipants = battleParticipantRepository.findByBattleRoom(room);
        long completedCount = allParticipants.stream()
                .filter(p -> p.getStatus() == BattleParticipantStatus.EXIT)
                .count();
        // PARTICIPANT_DONE 브로드캐스트
        messagingTemplate.convertAndSend(
                "/topic/room/" + roomId,
                Map.of("type", "PARTICIPANT_DONE", "userId", memberId, "rank", completedCount));

        /*
         * 전원 EXIT 체크
         *     → 아직 남은 사람 있으면 종료
         *     → 전원 완료면 battleResultService.settle() 호출
         *         → 순위/점수 계산
         *         → member.score 갱신
         *         → room.finish() → status: FINISHED
         *         → BATTLE_FINISHED 브로드캐스트
         */
        boolean allFinished = allParticipants.stream().allMatch(p -> p.getStatus() == BattleParticipantStatus.EXIT);
        if (allFinished) {
            battleResultService.settle(roomId);
        }
    }

    /**
     * 폴링 부분 (Judge0 worker가 코드를 실행 완료할 때까지 1초 간격으로 계속 물어보는 것)
     *   submitBatch() → 토큰 발급 ["abc", "def"]
     *        │
     *        ▼
     *   1초 후 getBatchResults() → status_id 1 (처리 중) → 다시 대기
     *   1초 후 getBatchResults() → status_id 1 (처리 중) → 다시 대기
     *   1초 후 getBatchResults() → status_id 3 (완료!) → 결과 반환
     */
    private List<Judge0SubmitResponse> runJudge(List<Judge0SubmitRequest> batchRequests) {
        try {
            List<String> tokens = judge0Client.submitBatch(batchRequests); // 제출 → 토큰 받기
            for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) { // 최대 10회
                List<Judge0SubmitResponse> results = judge0Client.getBatchResults(tokens); // 결과 조회
                if (results.stream().allMatch(Judge0SubmitResponse::isCompleted)) {
                    return results; // 모두 완료했으면 반환
                }
                Thread.sleep(POLL_INTERVAL_MS); // 아직 처리중이면 1초 대기
            }
            // 10초 지나도 안 끝나면 타임아웃
            log.warn("Judge0 polling timed out after {} attempts", MAX_POLL_ATTEMPTS);
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Judge0 polling interrupted", e);
            return List.of();
        } catch (Exception e) {
            log.error("Judge0 judging failed", e);
            return List.of();
        }
    }

    /**
     * 우선순위: CE > RE > TLE > WA > AC
     */
    private SubmissionResult aggregateResult(List<Judge0SubmitResponse> results, int totalCount) {
        if (results.isEmpty()) return SubmissionResult.RE;

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
