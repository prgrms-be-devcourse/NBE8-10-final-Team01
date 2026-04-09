package com.back.global.judge;

import java.util.List;

import org.springframework.stereotype.Service;

import com.back.global.judge.dto.Judge0SubmitRequest;
import com.back.global.judge.dto.Judge0SubmitResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class Judge0ExecutionService {

    private static final int JUDGE0_BATCH_LIMIT = 20;
    private static final int BASE_MAX_POLL_ATTEMPTS = 20;
    private static final int EXTRA_ATTEMPTS_PER_EXTRA_CHUNK = 10;
    private static final int EXTRA_ATTEMPTS_PER_10_CASES = 5;
    private static final int POLL_INTERVAL_MS = 1000;

    private final Judge0Client judge0Client;

    /**
     * Judge0에 배치 제출 후 폴링하여 결과 반환.
     * 타임아웃 또는 오류 시 빈 리스트 반환.
     */
    // TODO: 채점 폴링 10초 실패해서 빈 리스트를 반환하게되어서 WA로 된다면
    // 그건 나중에 점수 결산에서 빼야하는것 아닌가? (WA에서20초 추가되는거)
    public List<Judge0SubmitResponse> execute(List<Judge0SubmitRequest> batchRequests) {
        try {
            List<String> tokens = judge0Client.submitBatch(batchRequests);
            if (tokens.isEmpty()) {
                return List.of();
            }

            int chunkCount = Math.max(1, (tokens.size() + JUDGE0_BATCH_LIMIT - 1) / JUDGE0_BATCH_LIMIT);
            int caseScaledAttempts = ((batchRequests.size() + 9) / 10) * EXTRA_ATTEMPTS_PER_10_CASES;
            int maxPollAttempts =
                    BASE_MAX_POLL_ATTEMPTS + (chunkCount - 1) * EXTRA_ATTEMPTS_PER_EXTRA_CHUNK + caseScaledAttempts;

            List<Judge0SubmitResponse> lastResults = List.of();
            for (int i = 0; i < maxPollAttempts; i++) {
                try {
                    List<Judge0SubmitResponse> results = judge0Client.getBatchResults(tokens);
                    lastResults = results;
                    if (!results.isEmpty() && results.stream().allMatch(Judge0SubmitResponse::isCompleted)) {
                        return results;
                    }
                } catch (Exception pollException) {
                    // 일시적 조회 실패(네트워크/4xx/5xx)가 있어도 즉시 JUDGE_ERROR로 끝내지 않고 재시도한다.
                    log.warn("Judge0 polling attempt {}/{} failed", i + 1, maxPollAttempts, pollException);
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }

            // 타임아웃 직전 마지막 결과가 있으면 반환해 상위 계층에서 상태를 판단하게 한다.
            if (!lastResults.isEmpty()) {
                log.warn("Judge0 polling timed out after {} attempts with partial results", maxPollAttempts);
                return lastResults;
            }

            log.warn("Judge0 polling timed out after {} attempts without results", maxPollAttempts);
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Judge0 polling interrupted", e);
            return List.of();
        } catch (Exception e) {
            log.error("Judge0 execution failed", e);
            return List.of();
        }
    }

    public int getLanguageId(String language) {
        return switch (language.toLowerCase()) {
            case "python", "python3" -> 71;
            case "java" -> 62;
            case "cpp", "c++" -> 54;
            case "c" -> 50;
            case "javascript", "js" -> 63;
            default -> throw new IllegalArgumentException("지원하지 않는 언어: " + language);
        };
    }

    /**
     * 관리자 JSON 업로드에서 '\\n' 문자열이 저장된 과거 데이터를 런타임에서 안전하게 복원한다.
     */
    public String restoreEscapedNewline(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\r\n", "\n").replace("\\r\\n", "\n").replace("\\n", "\n");
    }
}
