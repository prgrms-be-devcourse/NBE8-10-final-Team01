package com.back.global.judge;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
        log.debug("submitBatch called with {} submissions", submissions.size());

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
            return tokenResponses.stream().map(TokenResponse::token).toList();

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Judge0 submitBatch error", e);
        }
    }

    /**
     * 토큰 목록으로 채점 결과를 조회한다.
     */
    public List<Judge0SubmitResponse> getBatchResults(List<String> tokens) {
        String tokenParam = String.join(",", tokens);
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
            return result.submissions();

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Judge0 getBatchResults error", e);
        }
    }

    private record TokenResponse(String token) {}

    private record BatchResultResponse(List<Judge0SubmitResponse> submissions) {}
}
