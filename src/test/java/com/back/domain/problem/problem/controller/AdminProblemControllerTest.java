package com.back.domain.problem.problem.controller;

import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import com.back.global.IntegrationTestBase;
import com.back.global.jwt.JwtProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.persistence.EntityManager;

@AutoConfigureMockMvc
@Transactional
class AdminProblemControllerTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtProvider jwtProvider;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from submissions");
        jdbcTemplate.update("delete from solo_submissions");
        jdbcTemplate.update("delete from battle_participants");
        jdbcTemplate.update("delete from battle_rooms");
        jdbcTemplate.update("delete from review_schedule");
        jdbcTemplate.update("delete from member_problem_first_solves");
        jdbcTemplate.update("delete from member_rating_profiles");
        jdbcTemplate.update("delete from problem_language_profiles");
        jdbcTemplate.update("delete from problem_tag_connect");
        jdbcTemplate.update("delete from problem_translations");
        jdbcTemplate.update("delete from test_cases");
        jdbcTemplate.update("delete from tags");
        jdbcTemplate.update("delete from problems");
        jdbcTemplate.update("delete from members");
    }

    @Test
    @DisplayName("관리자는 문제를 단건 등록할 수 있다")
    void createProblem_asAdmin_success() throws Exception {
        Long adminId = insertMember("admin@example.com", "admin", "ADMIN");

        mockMvc.perform(post("/api/v1/admin/problems")
                        .cookie(accessToken(adminId, "admin", "ROLE_ADMIN"))
                        .contentType("application/json")
                        .content(validPayload()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.problemId").isNumber())
                .andExpect(jsonPath("$.mode").value("CREATED"))
                .andExpect(jsonPath("$.sourceProblemId").value("LOCAL-1001"))
                .andExpect(jsonPath("$.title").value("누적합 최대 구간"));

        assertCount("problems", 1);
        assertCount("tags", 2);
        assertCount("problem_tag_connect", 2);
        assertCount("problem_language_profiles", 5);
        assertCount("test_cases", 13);
    }

    @Test
    @DisplayName("일반 사용자는 관리자 문제 등록 API에 접근할 수 없다")
    void createProblem_asUser_forbidden() throws Exception {
        Long userId = insertMember("user@example.com", "user", "USER");

        mockMvc.perform(post("/api/v1/admin/problems")
                        .cookie(accessToken(userId, "user", "ROLE_USER"))
                        .contentType("application/json")
                        .content(validPayload()))
                .andExpect(status().isForbidden());

        assertCount("problems", 0);
    }

    @Test
    @DisplayName("관리자 단건 등록 시 sourceProblemId가 비어 있으면 자동 생성된다")
    void createProblem_asAdmin_autoGenerateSourceProblemId_whenBlank() throws Exception {
        Long adminId = insertMember("admin2@example.com", "admin2", "ADMIN");

        mockMvc.perform(post("/api/v1/admin/problems")
                        .cookie(accessToken(adminId, "admin2", "ROLE_ADMIN"))
                        .contentType("application/json")
                        .content(validPayloadWithoutSourceProblemId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.problemId").isNumber())
                .andExpect(jsonPath("$.mode").value("CREATED"))
                .andExpect(jsonPath("$.sourceProblemId", startsWith("LOCAL-")))
                .andExpect(jsonPath("$.title").value("누적합 최대 구간"));

        assertCount("problem_language_profiles", 5);
        assertQueryCount("select count(*) from problem_language_profiles where is_default = true", 1);
    }

    @Test
    @DisplayName("관리자 단건 등록 시 문자열에 포함된 \\\\n은 실제 개행으로 정규화되어 저장된다")
    void createProblem_asAdmin_normalizesEscapedNewlineFields() throws Exception {
        Long adminId = insertMember("admin3@example.com", "admin3", "ADMIN");

        mockMvc.perform(post("/api/v1/admin/problems")
                        .cookie(accessToken(adminId, "admin3", "ROLE_ADMIN"))
                        .contentType("application/json")
                        .content(validPayloadWithLiteralEscapedNewline()))
                .andExpect(status().isCreated());

        entityManager.flush();
        String starterCode = jdbcTemplate.queryForObject("""
                select plp.starter_code
                from problem_language_profiles plp
                join problems p on p.id = plp.problem_id
                where p.source_problem_id = 'LOCAL-1001' and plp.language_code = 'python3'
                """, String.class);
        String sampleInput = jdbcTemplate.queryForObject("""
                select tc.input
                from test_cases tc
                join problems p on p.id = tc.problem_id
                where p.source_problem_id = 'LOCAL-1001' and tc.is_sample = true
                order by tc.id asc
                limit 1
                """, String.class);

        if (starterCode == null || !starterCode.contains("\n") || starterCode.contains("\\n")) {
            throw new AssertionError("starterCode 개행 정규화 실패: " + starterCode);
        }
        if (sampleInput == null || !sampleInput.contains("\n") || sampleInput.contains("\\n")) {
            throw new AssertionError("sample input 개행 정규화 실패: " + sampleInput);
        }
    }

    @Test
    @DisplayName("관리자 대량 검증은 sourceProblemId가 비어 있어도 유효하다")
    void validateBulk_asAdmin_allowBlankSourceProblemId() throws Exception {
        Long adminId = insertMember("admin4@example.com", "admin4", "ADMIN");

        mockMvc.perform(post("/api/v1/admin/problems/bulk/validate")
                        .cookie(accessToken(adminId, "admin4", "ROLE_ADMIN"))
                        .contentType("application/json")
                        .content(validBulkPayloadWithoutSourceProblemId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.validCount").value(2))
                .andExpect(jsonPath("$.errors.length()").value(0))
                .andExpect(jsonPath("$.validationToken").isString());
    }

    @Test
    @DisplayName("관리자 대량 검증은 요청 내 동일 문제 중복을 차단한다")
    void validateBulk_asAdmin_rejectDuplicateProblemInRequest() throws Exception {
        Long adminId = insertMember("admin4_2@example.com", "admin4_2", "ADMIN");

        mockMvc.perform(post("/api/v1/admin/problems/bulk/validate")
                        .cookie(accessToken(adminId, "admin4_2", "ROLE_ADMIN"))
                        .contentType("application/json")
                        .content(duplicateBulkPayloadWithoutSourceProblemId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.validCount").value(1))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("problem"))
                .andExpect(jsonPath("$.validationToken").isEmpty());
    }

    @Test
    @DisplayName("관리자 대량 등록은 validate 토큰이 있을 때만 가능하다")
    void importBulk_asAdmin_autoGenerateSourceProblemId_whenBlank() throws Exception {
        Long adminId = insertMember("admin5@example.com", "admin5", "ADMIN");
        String payload = validBulkPayloadWithoutSourceProblemId();
        String validationToken = requestBulkValidationToken(adminId, "admin5", payload);

        mockMvc.perform(post("/api/v1/admin/problems/bulk/import")
                        .cookie(accessToken(adminId, "admin5", "ROLE_ADMIN"))
                        .contentType("application/json")
                        .content(withValidationToken(payload, validationToken)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.inserted").value(2))
                .andExpect(jsonPath("$.updated").value(0))
                .andExpect(jsonPath("$.problemIds.length()").value(2));

        assertCount("problems", 2);
        assertQueryCount("select count(*) from problems where source_problem_id like 'LOCAL-%'", 2);
    }

    @Test
    @DisplayName("관리자 대량 등록은 validate 토큰 없이 요청하면 실패한다")
    void importBulk_asAdmin_fail_whenValidationTokenMissing() throws Exception {
        Long adminId = insertMember("admin5_2@example.com", "admin5_2", "ADMIN");

        mockMvc.perform(post("/api/v1/admin/problems/bulk/import")
                        .cookie(accessToken(adminId, "admin5_2", "ROLE_ADMIN"))
                        .contentType("application/json")
                        .content(validBulkPayloadWithoutSourceProblemId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.msg").value("대량 import 전에 validate를 먼저 수행하세요."));

        assertCount("problems", 0);
    }

    @Test
    @DisplayName("관리자 단건 검증은 정상 payload면 valid=true를 반환한다")
    void validateSingle_asAdmin_success() throws Exception {
        Long adminId = insertMember("admin6@example.com", "admin6", "ADMIN");

        mockMvc.perform(post("/api/v1/admin/problems/validate")
                        .cookie(accessToken(adminId, "admin6", "ROLE_ADMIN"))
                        .contentType("application/json")
                        .content(validPayloadWithoutSourceProblemId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.errors.length()").value(0));
    }

    @Test
    @DisplayName("관리자 단건 검증은 중복 sourceProblemId면 valid=false를 반환한다")
    void validateSingle_asAdmin_duplicateSourceProblemId() throws Exception {
        Long adminId = insertMember("admin7@example.com", "admin7", "ADMIN");

        mockMvc.perform(post("/api/v1/admin/problems")
                        .cookie(accessToken(adminId, "admin7", "ROLE_ADMIN"))
                        .contentType("application/json")
                        .content(validPayload()))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/admin/problems/validate")
                        .cookie(accessToken(adminId, "admin7", "ROLE_ADMIN"))
                        .contentType("application/json")
                        .content(validPayload()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].field").value("business"));
    }

    private Long insertMember(String email, String nickname, String role) {
        return jdbcTemplate.queryForObject("""
                insert into members (id, email, nickname, password, score, tier, role, created_at)
                values (nextval('member_id_seq'), ?, ?, 'password', 0, null, ?, now())
                returning id
                """, Long.class, email, nickname, role);
    }

    private MockCookie accessToken(Long memberId, String nickname, String roleKey) {
        return new MockCookie(
                "accessToken", jwtProvider.createToken(memberId, nickname + "@example.com", nickname, roleKey));
    }

    private void assertCount(String tableName, int expected) {
        // JdbcTemplate는 JPA 영속성 컨텍스트의 미플러시 변경을 보지 못할 수 있으므로 flush 후 조회한다.
        entityManager.flush();
        Integer count = jdbcTemplate.queryForObject("select count(*) from " + tableName, Integer.class);
        if (count == null || count != expected) {
            throw new AssertionError(
                    "count mismatch for " + tableName + ": expected=" + expected + ", actual=" + count);
        }
    }

    private void assertQueryCount(String query, int expected) {
        entityManager.flush();
        Integer count = jdbcTemplate.queryForObject(query, Integer.class);
        if (count == null || count != expected) {
            throw new AssertionError("count mismatch for query: expected=" + expected + ", actual=" + count);
        }
    }

    private String validPayload() {
        return """
                {
                  "sourceProblemId": "LOCAL-1001",
                  "title": "누적합 최대 구간",
                  "difficulty": "MEDIUM",
                  "content": "길이가 N인 수열에서 연속 부분합의 최댓값을 구하시오.",
                  "difficultyRating": 1600,
                  "timeLimitMs": 1000,
                  "memoryLimitMb": 256,
                  "inputFormat": "첫 줄에 N, 둘째 줄에 수열이 주어진다.",
                  "outputFormat": "최대 부분합을 출력한다.",
                  "inputMode": "STDIO",
                  "judgeType": "EXACT",
                  "checkerCode": null,
                  "tags": ["dp", "prefix-sum"],
                  "starterCodes": [
                    {"language": "python3", "code": "def solve():\\n    pass", "isDefault": true},
                    {"language": "java", "code": "public class Main { public static void main(String[] args) {} }", "isDefault": false},
                    {"language": "cpp17", "code": "#include <bits/stdc++.h>\\nint main(){}", "isDefault": false},
                    {"language": "c", "code": "#include <stdio.h>\\nint main(){return 0;}", "isDefault": false},
                    {"language": "javascript", "code": "function solve() {}", "isDefault": false}
                  ],
                  "sampleCases": [
                    {"input": "5\\n1 -2 3 4 -1", "output": "7"},
                    {"input": "4\\n-1 -2 -3 -4", "output": "-1"},
                    {"input": "3\\n2 2 2", "output": "6"}
                  ],
                  "hiddenCases": [
                    {"input": "1\\n100", "output": "100"},
                    {"input": "1\\n-100", "output": "-100"},
                    {"input": "6\\n1 2 -10 3 4 5", "output": "12"},
                    {"input": "7\\n-2 1 -3 4 -1 2 1", "output": "6"},
                    {"input": "5\\n0 0 0 0 0", "output": "0"},
                    {"input": "4\\n100 -1 -1 -1", "output": "100"},
                    {"input": "4\\n-1 100 -1 -1", "output": "100"},
                    {"input": "4\\n-1 -1 100 -1", "output": "100"},
                    {"input": "4\\n-1 -1 -1 100", "output": "100"},
                    {"input": "8\\n1 -1 1 -1 1 -1 1 -1", "output": "1"}
                  ]
                }
                """;
    }

    private String validPayloadWithoutSourceProblemId() {
        return """
                {
                  "sourceProblemId": "",
                  "title": "누적합 최대 구간",
                  "difficulty": "MEDIUM",
                  "content": "길이가 N인 수열에서 연속 부분합의 최댓값을 구하시오.",
                  "difficultyRating": 1600,
                  "timeLimitMs": 1000,
                  "memoryLimitMb": 256,
                  "inputFormat": "첫 줄에 N, 둘째 줄에 수열이 주어진다.",
                  "outputFormat": "최대 부분합을 출력한다.",
                  "inputMode": "STDIO",
                  "judgeType": "EXACT",
                  "checkerCode": null,
                  "tags": ["dp", "prefix-sum"],
                  "sampleCases": [
                    {"input": "5\\n1 -2 3 4 -1", "output": "7"},
                    {"input": "4\\n-1 -2 -3 -4", "output": "-1"},
                    {"input": "3\\n2 2 2", "output": "6"}
                  ],
                  "hiddenCases": [
                    {"input": "1\\n100", "output": "100"},
                    {"input": "1\\n-100", "output": "-100"},
                    {"input": "6\\n1 2 -10 3 4 5", "output": "12"},
                    {"input": "7\\n-2 1 -3 4 -1 2 1", "output": "6"},
                    {"input": "5\\n0 0 0 0 0", "output": "0"},
                    {"input": "4\\n100 -1 -1 -1", "output": "100"},
                    {"input": "4\\n-1 100 -1 -1", "output": "100"},
                    {"input": "4\\n-1 -1 100 -1", "output": "100"},
                    {"input": "4\\n-1 -1 -1 100", "output": "100"},
                    {"input": "8\\n1 -1 1 -1 1 -1 1 -1", "output": "1"}
                  ]
                }
                """;
    }

    private String validPayloadWithLiteralEscapedNewline() {
        return validPayload().replace("\\n", "\\\\n");
    }

    private String validBulkPayloadWithoutSourceProblemId() {
        String first = validPayloadWithoutSourceProblemId();
        String second = validPayloadWithoutSourceProblemId().replace("누적합 최대 구간", "누적합 최대 구간 2");
        return """
                {
                  "problems": [
                    %s,
                    %s
                  ]
                }
                """.formatted(first, second);
    }

    private String requestBulkValidationToken(Long adminId, String nickname, String payload) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/problems/bulk/validate")
                        .cookie(accessToken(adminId, nickname, "ROLE_ADMIN"))
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors.length()").value(0))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode token = root.get("validationToken");
        if (token == null || token.isNull() || token.asText().isBlank()) {
            throw new AssertionError("validationToken이 비어 있습니다.");
        }
        return token.asText();
    }

    private String withValidationToken(String payload, String validationToken) {
        return """
                {
                  "problems": %s,
                  "validationToken": "%s"
                }
                """.formatted(extractProblemsArray(payload), validationToken);
    }

    private String duplicateBulkPayloadWithoutSourceProblemId() {
        String first = validPayloadWithoutSourceProblemId();
        String second = validPayloadWithoutSourceProblemId();
        return """
                {
                  "problems": [
                    %s,
                    %s
                  ]
                }
                """.formatted(first, second);
    }

    private String extractProblemsArray(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode problems = root.get("problems");
            if (problems == null || !problems.isArray()) {
                throw new IllegalArgumentException("payload에 problems 배열이 없습니다.");
            }
            return objectMapper.writeValueAsString(problems);
        } catch (Exception ex) {
            throw new IllegalArgumentException("payload에서 problems 배열 추출 실패", ex);
        }
    }
}
