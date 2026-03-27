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

    private static final int MAX_POLL_ATTEMPTS = 10;
    private static final int POLL_INTERVAL_MS = 1000;

    private final Judge0Client judge0Client;

    /**
     * Judge0에 배치 제출 후 폴링하여 결과 반환.
     * 타임아웃(10초) 또는 오류 시 빈 리스트 반환.
     */
    public List<Judge0SubmitResponse> execute(List<Judge0SubmitRequest> batchRequests) {
        try {
            List<String> tokens = judge0Client.submitBatch(batchRequests);
            for (int i = 0; i < MAX_POLL_ATTEMPTS; i++) {
                List<Judge0SubmitResponse> results = judge0Client.getBatchResults(tokens);
                if (results.stream().allMatch(Judge0SubmitResponse::isCompleted)) {
                    return results;
                }
                Thread.sleep(POLL_INTERVAL_MS);
            }
            log.warn("Judge0 polling timed out after {} attempts", MAX_POLL_ATTEMPTS);
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
}
