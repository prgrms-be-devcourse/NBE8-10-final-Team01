package com.back.global.judge;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.back.global.judge.dto.Judge0SubmitRequest;
import com.back.global.judge.dto.Judge0SubmitResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class Judge0Client {

    private static final int JUDGE0_BATCH_LIMIT = 20;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public Judge0Client(@Value("${judge0.url}") String baseUrl, ObjectMapper objectMapper) {
        this.baseUrl = baseUrl;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().build();
    }

    /**
     * 테스트케이스 목록을 Judge0 Batch API로 일괄 제출하고 토큰 목록을 반환한다.
     * Judge0는 snake_case 필드명(source_code, language_id 등)을 요구한다.
     */
    public List<String> submitBatch(List<Judge0SubmitRequest> submissions) {
        if (submissions == null || submissions.isEmpty()) {
            return List.of();
        }
        log.debug("submitBatch called with {} submissions", submissions.size());

        List<String> allTokens = new ArrayList<>(submissions.size());
        for (int from = 0; from < submissions.size(); from += JUDGE0_BATCH_LIMIT) {
            int to = Math.min(from + JUDGE0_BATCH_LIMIT, submissions.size());
            List<Judge0SubmitRequest> chunk = submissions.subList(from, to);
            allTokens.addAll(submitBatchChunkWithFallback(chunk));
        }
        return allTokens;
    }

    /**
     * 토큰 목록으로 채점 결과를 조회한다.
     */
    public List<Judge0SubmitResponse> getBatchResults(List<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return List.of();
        }

        List<Judge0SubmitResponse> allResults = new ArrayList<>(tokens.size());
        for (int from = 0; from < tokens.size(); from += JUDGE0_BATCH_LIMIT) {
            int to = Math.min(from + JUDGE0_BATCH_LIMIT, tokens.size());
            List<String> chunk = tokens.subList(from, to);
            allResults.addAll(getBatchResultsChunkWithFallback(chunk));
        }
        return allResults;
    }

    /**
     * Judge0 배치 호출이 실패하면 청크를 반으로 쪼개 재시도한다.
     * 배포 환경별 request/body 제한 차이로 인한 일시적 4xx를 완화하기 위한 방어 로직.
     */
    private List<String> submitBatchChunkWithFallback(List<Judge0SubmitRequest> submissions) {
        try {
            return submitBatchChunk(submissions);
        } catch (RuntimeException e) {
            if (submissions.size() <= 1) {
                return List.of(submitSingle(submissions.get(0)));
            }
            int mid = submissions.size() / 2;
            log.warn("Judge0 batch submit failed for chunk size {}. Split and retry.", submissions.size(), e);
            List<String> left = submitBatchChunkWithFallback(new ArrayList<>(submissions.subList(0, mid)));
            List<String> right =
                    submitBatchChunkWithFallback(new ArrayList<>(submissions.subList(mid, submissions.size())));

            List<String> merged = new ArrayList<>(left.size() + right.size());
            merged.addAll(left);
            merged.addAll(right);
            return merged;
        }
    }

    private List<String> submitBatchChunk(List<Judge0SubmitRequest> submissions) {
        List<Map<String, Object>> submissionMaps = submissions.stream()
                .map(s -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("source_code", s.sourceCode());
                    map.put("language_id", s.languageId());
                    map.put("stdin", s.stdin() != null ? s.stdin() : "");
                    if (s.expectedOutput() != null) {
                        map.put("expected_output", s.expectedOutput());
                    }
                    return map;
                })
                .toList();

        try {
            String requestBodyJson = objectMapper.writeValueAsString(Map.of("submissions", submissionMaps));
            log.debug("Judge0 batch request body: {}", requestBodyJson);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/submissions/batch"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.debug("Judge0 batch response status: {}, body: {}", response.statusCode(), response.body());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException(
                        "Judge0 batch submit failed: " + response.statusCode() + " " + response.body());
            }

            List<TokenResponse> tokenResponses =
                    objectMapper.readValue(response.body(), new TypeReference<List<TokenResponse>>() {});
            if (tokenResponses.size() != submissions.size()) {
                throw new RuntimeException("Judge0 batch submit mismatch: requested=" + submissions.size()
                        + ", received=" + tokenResponses.size());
            }
            return tokenResponses.stream().map(TokenResponse::token).toList();

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Judge0 submitBatch error", e);
        }
    }

    /**
     * 단건 제출 폴백.
     */
    private String submitSingle(Judge0SubmitRequest submission) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("source_code", submission.sourceCode());
        body.put("language_id", submission.languageId());
        body.put("stdin", submission.stdin() != null ? submission.stdin() : "");
        if (submission.expectedOutput() != null) {
            body.put("expected_output", submission.expectedOutput());
        }

        try {
            String requestBodyJson = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/submissions"))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException(
                        "Judge0 single submit failed: " + response.statusCode() + " " + response.body());
            }

            TokenResponse tokenResponse = objectMapper.readValue(response.body(), TokenResponse.class);
            if (tokenResponse.token() == null || tokenResponse.token().isBlank()) {
                throw new RuntimeException("Judge0 single submit returned empty token");
            }
            return tokenResponse.token();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Judge0 submitSingle error", e);
        }
    }

    private List<Judge0SubmitResponse> getBatchResultsChunk(List<String> tokens) {
        String tokenParam = tokens.stream()
                .map(t -> URLEncoder.encode(t, StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/submissions/batch?tokens=" + tokenParam))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException(
                        "Judge0 getBatchResults failed: " + response.statusCode() + " " + response.body());
            }

            BatchResultResponse result = objectMapper.readValue(response.body(), BatchResultResponse.class);
            if (result.submissions() == null || result.submissions().size() != tokens.size()) {
                throw new RuntimeException("Judge0 batch result mismatch: requested=" + tokens.size() + ", received="
                        + (result.submissions() == null
                                ? 0
                                : result.submissions().size()));
            }
            return result.submissions();

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Judge0 getBatchResults error", e);
        }
    }

    /**
     * 결과 조회 실패 시에도 제출 순서를 유지한 채 분할 재시도한다.
     */
    private List<Judge0SubmitResponse> getBatchResultsChunkWithFallback(List<String> tokens) {
        try {
            return getBatchResultsChunk(tokens);
        } catch (RuntimeException e) {
            if (tokens.size() <= 1) {
                return List.of(getResultByToken(tokens.get(0)));
            }
            int mid = tokens.size() / 2;
            log.warn("Judge0 batch result fetch failed for chunk size {}. Split and retry.", tokens.size(), e);
            List<Judge0SubmitResponse> left = getBatchResultsChunkWithFallback(new ArrayList<>(tokens.subList(0, mid)));
            List<Judge0SubmitResponse> right =
                    getBatchResultsChunkWithFallback(new ArrayList<>(tokens.subList(mid, tokens.size())));

            List<Judge0SubmitResponse> merged = new ArrayList<>(left.size() + right.size());
            merged.addAll(left);
            merged.addAll(right);
            return merged;
        }
    }

    /**
     * 단건 결과 조회 폴백.
     */
    private Judge0SubmitResponse getResultByToken(String token) {
        String encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/submissions/" + encodedToken))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException(
                        "Judge0 single result failed: " + response.statusCode() + " " + response.body());
            }

            return objectMapper.readValue(response.body(), Judge0SubmitResponse.class);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Judge0 getResultByToken error", e);
        }
    }

    private record TokenResponse(String token) {}

    private record BatchResultResponse(List<Judge0SubmitResponse> submissions) {}
}
