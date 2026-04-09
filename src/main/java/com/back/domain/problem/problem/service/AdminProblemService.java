package com.back.domain.problem.problem.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.back.domain.problem.languageprofile.entity.ProblemLanguageProfile;
import com.back.domain.problem.languageprofile.repository.ProblemLanguageProfileRepository;
import com.back.domain.problem.problem.dto.AdminProblemBulkImportResponse;
import com.back.domain.problem.problem.dto.AdminProblemBulkRequest;
import com.back.domain.problem.problem.dto.AdminProblemBulkValidateResponse;
import com.back.domain.problem.problem.dto.AdminProblemMutationResponse;
import com.back.domain.problem.problem.dto.AdminProblemSingleValidateResponse;
import com.back.domain.problem.problem.dto.AdminProblemUpsertRequest;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.problem.problem.enums.InputMode;
import com.back.domain.problem.problem.enums.JudgeType;
import com.back.domain.problem.problem.repository.ProblemRepository;
import com.back.domain.problem.testcase.entity.TestCase;
import com.back.domain.problem.testcase.repository.TestCaseRepository;
import com.back.domain.tag.problemtagconnect.entity.ProblemTagConnect;
import com.back.domain.tag.problemtagconnect.repository.ProblemTagConnectRepository;
import com.back.domain.tag.tag.entity.Tag;
import com.back.domain.tag.tag.repository.TagRepository;
import com.back.global.exception.ServiceException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminProblemService {
    private static final Duration BULK_VALIDATION_TTL = Duration.ofMinutes(10);

    private static final String PYTHON3_STARTER = """
            def solve():
                pass

            if __name__ == "__main__":
                solve()
            """;
    private static final String JAVA_STARTER = """
            import java.io.*;
            import java.util.*;

            public class Main {
                public static void main(String[] args) throws Exception {
                    // TODO
                }
            }
            """;
    private static final String CPP17_STARTER = """
            #include <bits/stdc++.h>
            using namespace std;

            int main() {
                ios::sync_with_stdio(false);
                cin.tie(nullptr);
                // TODO
                return 0;
            }
            """;
    private static final String C_STARTER = """
            #include <stdio.h>

            int main(void) {
                // TODO
                return 0;
            }
            """;
    private static final String JAVASCRIPT_STARTER = """
            'use strict';

            function solve(input) {
              // TODO
              return '';
            }

            const fs = require('fs');
            const input = fs.readFileSync(0, 'utf8');
            const output = solve(input);
            if (output !== undefined) {
              process.stdout.write(String(output));
            }
            """;

    private final ProblemRepository problemRepository;
    private final ProblemLanguageProfileRepository problemLanguageProfileRepository;
    private final ProblemTagConnectRepository problemTagConnectRepository;
    private final TestCaseRepository testCaseRepository;
    private final TagRepository tagRepository;
    private final ConcurrentHashMap<String, BulkValidationState> bulkValidationStateMap = new ConcurrentHashMap<>();

    private record BulkValidationState(String payloadHash, Instant expiresAt) {}

    @Transactional
    public AdminProblemMutationResponse createProblem(AdminProblemUpsertRequest request) {
        AdminProblemUpsertRequest normalizedRequest = normalizeEscapedNewlineFields(request);
        String sourceProblemId = resolveSourceProblemIdForCreate(normalizedRequest);
        validateBusinessRules(normalizedRequest, null, sourceProblemId);
        Problem problem = problemRepository
                .findBySourceProblemId(sourceProblemId)
                .orElseGet(() -> Problem.create(
                        sourceProblemId,
                        normalizedRequest.title().trim(),
                        normalizedRequest.difficulty(),
                        normalizedRequest.content(),
                        normalizedRequest.difficultyRating(),
                        normalizedRequest.timeLimitMs(),
                        normalizedRequest.memoryLimitMb(),
                        normalizedRequest.inputFormat(),
                        normalizedRequest.outputFormat(),
                        normalizedRequest.inputMode(),
                        normalizedRequest.judgeType(),
                        normalizedRequest.checkerCode()));

        if (problem.getId() != null) {
            throw new ServiceException("400-1", "이미 존재하는 sourceProblemId입니다: " + sourceProblemId);
        }

        problemRepository.save(problem);
        replaceAssociations(problem, normalizedRequest);

        return new AdminProblemMutationResponse(
                problem.getId(), "CREATED", problem.getSourceProblemId(), problem.getTitle());
    }

    @Transactional
    public AdminProblemMutationResponse updateProblem(Long problemId, AdminProblemUpsertRequest request) {
        AdminProblemUpsertRequest normalizedRequest = normalizeEscapedNewlineFields(request);
        Problem problem = problemRepository
                .findById(problemId)
                .orElseThrow(() -> new ServiceException("404-1", "존재하지 않는 문제입니다."));

        String sourceProblemId = resolveSourceProblemIdForUpdate(problem, normalizedRequest);
        validateBusinessRules(normalizedRequest, problemId, sourceProblemId);

        problem.update(
                sourceProblemId,
                normalizedRequest.title().trim(),
                normalizedRequest.difficulty(),
                normalizedRequest.content(),
                normalizedRequest.difficultyRating(),
                normalizedRequest.timeLimitMs(),
                normalizedRequest.memoryLimitMb(),
                normalizedRequest.inputFormat(),
                normalizedRequest.outputFormat(),
                normalizedRequest.inputMode(),
                normalizedRequest.judgeType(),
                normalizedRequest.checkerCode());

        replaceAssociations(problem, normalizedRequest);

        return new AdminProblemMutationResponse(
                problem.getId(), "UPDATED", problem.getSourceProblemId(), problem.getTitle());
    }

    public AdminProblemBulkValidateResponse validateBulk(AdminProblemBulkRequest request) {
        clearExpiredValidationStates();

        List<AdminProblemBulkValidateResponse.ValidationError> errors = new ArrayList<>();
        Set<Integer> invalidRows = new HashSet<>();
        Set<String> seenSourceProblemIds = new HashSet<>();
        Set<String> seenProblemSignatures = new HashSet<>();

        for (int index = 0; index < request.problems().size(); index++) {
            AdminProblemUpsertRequest row =
                    normalizeEscapedNewlineFields(request.problems().get(index));
            try {
                String sourceProblemId = normalizeOptional(row.sourceProblemId());
                validateBusinessRules(row, null, sourceProblemId);
                if (!isBlank(sourceProblemId) && !seenSourceProblemIds.add(sourceProblemId)) {
                    addBulkValidationError(
                            errors,
                            invalidRows,
                            index,
                            "sourceProblemId",
                            "요청 내 sourceProblemId가 중복되었습니다: " + sourceProblemId);
                }

                String problemSignature = buildProblemSignature(row);
                if (!seenProblemSignatures.add(problemSignature)) {
                    addBulkValidationError(errors, invalidRows, index, "problem", "요청 내 동일한 문제가 중복되었습니다.");
                }
            } catch (ServiceException ex) {
                addBulkValidationError(errors, invalidRows, index, "business", ex.getMessage());
            }
        }

        int total = request.problems().size();
        int validCount = Math.max(total - invalidRows.size(), 0);
        String validationToken = null;
        if (errors.isEmpty() && isBlank(request.validationToken())) {
            validationToken = issueBulkValidationToken(computeBulkPayloadHash(request.problems()));
        }
        return new AdminProblemBulkValidateResponse(total, validCount, errors, validationToken);
    }

    public AdminProblemSingleValidateResponse validateSingle(AdminProblemUpsertRequest request) {
        AdminProblemUpsertRequest normalizedRequest = normalizeEscapedNewlineFields(request);
        List<AdminProblemSingleValidateResponse.ValidationError> errors = new ArrayList<>();
        try {
            String sourceProblemId = resolveSourceProblemIdForCreate(normalizedRequest);
            validateBusinessRules(normalizedRequest, null, sourceProblemId);
        } catch (ServiceException ex) {
            errors.add(new AdminProblemSingleValidateResponse.ValidationError("business", ex.getMessage()));
        }
        return new AdminProblemSingleValidateResponse(errors.isEmpty(), errors);
    }

    @Transactional
    public AdminProblemBulkImportResponse importBulk(AdminProblemBulkRequest request) {
        ensureBulkValidated(request);

        int inserted = 0;
        int updated = 0;
        List<Long> problemIds = new ArrayList<>();

        for (AdminProblemUpsertRequest row : request.problems()) {
            row = normalizeEscapedNewlineFields(row);
            String sourceProblemId = normalizeOptional(row.sourceProblemId());
            if (isBlank(sourceProblemId)) {
                // bulk에서도 sourceProblemId를 비우면 단건 생성과 동일하게 자동 발급한다.
                sourceProblemId = generateUniqueSourceProblemId();
            }

            Problem problem =
                    problemRepository.findBySourceProblemId(sourceProblemId).orElse(null);

            if (problem == null) {
                problem = Problem.create(
                        sourceProblemId,
                        row.title().trim(),
                        row.difficulty(),
                        row.content(),
                        row.difficultyRating(),
                        row.timeLimitMs(),
                        row.memoryLimitMb(),
                        row.inputFormat(),
                        row.outputFormat(),
                        row.inputMode(),
                        row.judgeType(),
                        row.checkerCode());
                problemRepository.save(problem);
                inserted++;
            } else {
                problem.update(
                        sourceProblemId,
                        row.title().trim(),
                        row.difficulty(),
                        row.content(),
                        row.difficultyRating(),
                        row.timeLimitMs(),
                        row.memoryLimitMb(),
                        row.inputFormat(),
                        row.outputFormat(),
                        row.inputMode(),
                        row.judgeType(),
                        row.checkerCode());
                updated++;
            }

            replaceAssociations(problem, row);
            problemIds.add(problem.getId());
        }

        return new AdminProblemBulkImportResponse(request.problems().size(), inserted, updated, problemIds);
    }

    private void validateBusinessRules(
            AdminProblemUpsertRequest request, Long currentProblemId, String sourceProblemId) {
        if (!isBlank(sourceProblemId)) {
            problemRepository.findBySourceProblemId(sourceProblemId).ifPresent(existing -> {
                if (currentProblemId == null || !existing.getId().equals(currentProblemId)) {
                    throw new ServiceException("400-1", "이미 존재하는 sourceProblemId입니다: " + sourceProblemId);
                }
            });
        }

        List<Long> duplicateIds = problemRepository.findIdsBySignature(
                request.title().trim(),
                request.content(),
                request.difficulty(),
                request.difficultyRating(),
                request.timeLimitMs(),
                request.memoryLimitMb(),
                request.inputMode() != null ? request.inputMode() : InputMode.STDIO,
                request.judgeType() != null ? request.judgeType() : JudgeType.EXACT,
                request.inputFormat(),
                request.outputFormat(),
                request.checkerCode());
        boolean hasDuplicateProblem = duplicateIds.stream()
                .anyMatch(existingProblemId -> currentProblemId == null || !existingProblemId.equals(currentProblemId));
        if (hasDuplicateProblem) {
            throw new ServiceException("400-1", "이미 동일한 내용/제약의 문제가 존재합니다.");
        }

        if (request.starterCodes() != null && !request.starterCodes().isEmpty()) {
            if (request.starterCodes().size() < 5) {
                throw new ServiceException("400-1", "starterCodes는 최소 5개 이상이어야 합니다.");
            }

            Set<String> languages = new HashSet<>();
            int defaultCount = 0;
            for (AdminProblemUpsertRequest.StarterCodeRequest starterCode : request.starterCodes()) {
                String language = normalizeRequired(starterCode.language(), "starterCodes.language는 필수입니다.");
                if (!languages.add(language)) {
                    throw new ServiceException("400-1", "starterCodes의 language가 중복되었습니다: " + language);
                }
                if (Boolean.TRUE.equals(starterCode.isDefault())) {
                    defaultCount++;
                }
            }

            if (defaultCount != 1) {
                throw new ServiceException("400-1", "starterCodes는 isDefault=true가 정확히 1개여야 합니다.");
            }
        }

        if (request.sampleCases() == null || request.sampleCases().size() < 3) {
            throw new ServiceException("400-1", "sampleCases는 최소 3개 이상이어야 합니다.");
        }
        if (request.hiddenCases() == null || request.hiddenCases().size() < 10) {
            throw new ServiceException("400-1", "hiddenCases는 최소 10개 이상이어야 합니다.");
        }

        JudgeType judgeType = request.judgeType() != null ? request.judgeType() : JudgeType.EXACT;
        if (judgeType == JudgeType.CHECKER && isBlank(request.checkerCode())) {
            throw new ServiceException("400-1", "judgeType이 CHECKER인 경우 checkerCode는 필수입니다.");
        }
    }

    private String resolveSourceProblemIdForCreate(AdminProblemUpsertRequest request) {
        String sourceProblemId = normalizeOptional(request.sourceProblemId());
        if (!isBlank(sourceProblemId)) {
            return sourceProblemId;
        }
        return generateUniqueSourceProblemId();
    }

    private String resolveSourceProblemIdForUpdate(Problem problem, AdminProblemUpsertRequest request) {
        String sourceProblemId = normalizeOptional(request.sourceProblemId());
        if (!isBlank(sourceProblemId)) {
            return sourceProblemId;
        }

        String existing = normalizeOptional(problem.getSourceProblemId());
        if (!isBlank(existing)) {
            return existing;
        }
        return generateUniqueSourceProblemId();
    }

    private String generateUniqueSourceProblemId() {
        for (int attempt = 0; attempt < 10; attempt++) {
            String token = UUID.randomUUID()
                    .toString()
                    .replace("-", "")
                    .substring(0, 20)
                    .toUpperCase(Locale.ROOT);
            String candidate = "LOCAL-" + token;
            if (problemRepository.findBySourceProblemId(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new ServiceException("500-1", "sourceProblemId 자동 생성에 실패했습니다.");
    }

    private void ensureBulkValidated(AdminProblemBulkRequest request) {
        clearExpiredValidationStates();

        String validationToken = normalizeOptional(request.validationToken());
        if (isBlank(validationToken)) {
            throw new ServiceException("400-1", "대량 import 전에 validate를 먼저 수행하세요.");
        }

        BulkValidationState state = bulkValidationStateMap.remove(validationToken);
        if (state == null || state.expiresAt().isBefore(Instant.now())) {
            throw new ServiceException("400-1", "validate 토큰이 만료되었거나 이미 사용되었습니다. 다시 validate 해주세요.");
        }

        String payloadHash = computeBulkPayloadHash(request.problems());
        if (!state.payloadHash().equals(payloadHash)) {
            throw new ServiceException("400-1", "validate 이후 요청 본문이 변경되었습니다. 다시 validate 해주세요.");
        }
    }

    private String issueBulkValidationToken(String payloadHash) {
        clearExpiredValidationStates();

        String token = UUID.randomUUID().toString().replace("-", "");
        bulkValidationStateMap.put(
                token, new BulkValidationState(payloadHash, Instant.now().plus(BULK_VALIDATION_TTL)));
        return token;
    }

    private void clearExpiredValidationStates() {
        Instant now = Instant.now();
        bulkValidationStateMap
                .entrySet()
                .removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private String computeBulkPayloadHash(List<AdminProblemUpsertRequest> rows) {
        MessageDigest digest = getSha256Digest();

        for (AdminProblemUpsertRequest row : rows) {
            AdminProblemUpsertRequest normalizedRow = normalizeEscapedNewlineFields(row);
            digest.update(buildProblemSignature(normalizedRow).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0x1E);
        }

        return toHex(digest.digest());
    }

    private String buildProblemSignature(AdminProblemUpsertRequest request) {
        StringBuilder signatureBuilder = new StringBuilder(1024);
        appendSignaturePart(signatureBuilder, normalizeOptional(request.title()));
        appendSignaturePart(
                signatureBuilder,
                request.difficulty() != null ? request.difficulty().name() : null);
        appendSignaturePart(signatureBuilder, normalizeOptional(request.content()));
        appendSignaturePart(
                signatureBuilder,
                request.difficultyRating() != null ? request.difficultyRating().toString() : null);
        appendSignaturePart(
                signatureBuilder,
                request.timeLimitMs() != null ? request.timeLimitMs().toString() : null);
        appendSignaturePart(
                signatureBuilder,
                request.memoryLimitMb() != null ? request.memoryLimitMb().toString() : null);
        appendSignaturePart(signatureBuilder, normalizeOptional(request.inputFormat()));
        appendSignaturePart(signatureBuilder, normalizeOptional(request.outputFormat()));
        appendSignaturePart(
                signatureBuilder, (request.inputMode() != null ? request.inputMode() : InputMode.STDIO).name());
        appendSignaturePart(
                signatureBuilder, (request.judgeType() != null ? request.judgeType() : JudgeType.EXACT).name());
        appendSignaturePart(signatureBuilder, normalizeOptional(request.checkerCode()));

        if (request.tags() != null) {
            for (String tag : request.tags()) {
                appendSignaturePart(signatureBuilder, normalizeOptional(tag));
            }
        }

        if (request.starterCodes() != null) {
            for (AdminProblemUpsertRequest.StarterCodeRequest starterCode : request.starterCodes()) {
                appendSignaturePart(signatureBuilder, normalizeOptional(starterCode.language()));
                appendSignaturePart(signatureBuilder, normalizeOptional(starterCode.code()));
                appendSignaturePart(
                        signatureBuilder,
                        starterCode.isDefault() != null
                                ? starterCode.isDefault().toString()
                                : null);
            }
        }

        if (request.sampleCases() != null) {
            for (AdminProblemUpsertRequest.TestCaseRequest sample : request.sampleCases()) {
                appendSignaturePart(signatureBuilder, normalizeOptional(sample.input()));
                appendSignaturePart(signatureBuilder, normalizeOptional(sample.output()));
            }
        }

        if (request.hiddenCases() != null) {
            for (AdminProblemUpsertRequest.TestCaseRequest hidden : request.hiddenCases()) {
                appendSignaturePart(signatureBuilder, normalizeOptional(hidden.input()));
                appendSignaturePart(signatureBuilder, normalizeOptional(hidden.output()));
            }
        }

        return signatureBuilder.toString();
    }

    private void appendSignaturePart(StringBuilder signatureBuilder, String value) {
        if (value != null) {
            signatureBuilder.append(value);
        }
        signatureBuilder.append('\u001F');
    }

    private void addBulkValidationError(
            List<AdminProblemBulkValidateResponse.ValidationError> errors,
            Set<Integer> invalidRows,
            int index,
            String field,
            String message) {
        errors.add(new AdminProblemBulkValidateResponse.ValidationError(index, field, message));
        invalidRows.add(index);
    }

    private MessageDigest getSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", ex);
        }
    }

    private String toHex(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte b : value) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }

    private void replaceAssociations(Problem problem, AdminProblemUpsertRequest request) {
        problemTagConnectRepository.deleteByProblemId(problem.getId());
        problemLanguageProfileRepository.deleteByProblemId(problem.getId());
        testCaseRepository.deleteByProblemId(problem.getId());

        List<ProblemTagConnect> connects = buildTagConnects(problem, request.tags());
        if (!connects.isEmpty()) {
            problemTagConnectRepository.saveAll(connects);
        }

        List<ProblemLanguageProfile> profiles = resolveStarterCodes(request.starterCodes()).stream()
                .map(starterCode -> ProblemLanguageProfile.create(
                        problem, starterCode.language().trim(), starterCode.code(), starterCode.isDefault()))
                .toList();
        problemLanguageProfileRepository.saveAll(profiles);

        List<TestCase> testCases = new ArrayList<>();
        testCases.addAll(request.sampleCases().stream()
                .map(sample -> TestCase.create(problem, sample.input(), sample.output(), true))
                .toList());
        testCases.addAll(request.hiddenCases().stream()
                .map(hidden -> TestCase.create(problem, hidden.input(), hidden.output(), false))
                .toList());
        testCaseRepository.saveAll(testCases);
    }

    private List<ProblemTagConnect> buildTagConnects(Problem problem, List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }

        Set<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            if (isBlank(tag)) {
                continue;
            }
            normalized.add(tag.trim());
        }

        if (normalized.isEmpty()) {
            return List.of();
        }

        List<ProblemTagConnect> connects = new ArrayList<>();
        for (String name : normalized) {
            Tag tag = tagRepository.findByName(name).orElseGet(() -> tagRepository.save(Tag.create(name)));
            connects.add(ProblemTagConnect.create(problem, tag));
        }
        return connects;
    }

    private List<AdminProblemUpsertRequest.StarterCodeRequest> resolveStarterCodes(
            List<AdminProblemUpsertRequest.StarterCodeRequest> starterCodes) {
        if (starterCodes != null && !starterCodes.isEmpty()) {
            return starterCodes;
        }

        // starterCodes가 누락되면 기본 5개 언어 템플릿을 자동 생성한다.
        return List.of(
                new AdminProblemUpsertRequest.StarterCodeRequest("python3", PYTHON3_STARTER, true),
                new AdminProblemUpsertRequest.StarterCodeRequest("java", JAVA_STARTER, false),
                new AdminProblemUpsertRequest.StarterCodeRequest("cpp17", CPP17_STARTER, false),
                new AdminProblemUpsertRequest.StarterCodeRequest("c", C_STARTER, false),
                new AdminProblemUpsertRequest.StarterCodeRequest("javascript", JAVASCRIPT_STARTER, false));
    }

    private AdminProblemUpsertRequest normalizeEscapedNewlineFields(AdminProblemUpsertRequest request) {
        return new AdminProblemUpsertRequest(
                request.sourceProblemId(),
                request.title(),
                request.difficulty(),
                normalizeEscapedNewlineText(request.content()),
                request.difficultyRating(),
                request.timeLimitMs(),
                request.memoryLimitMb(),
                normalizeEscapedNewlineText(request.inputFormat()),
                normalizeEscapedNewlineText(request.outputFormat()),
                request.inputMode(),
                request.judgeType(),
                normalizeEscapedNewlineText(request.checkerCode()),
                request.tags(),
                normalizeStarterCodes(request.starterCodes()),
                normalizeTestCases(request.sampleCases()),
                normalizeTestCases(request.hiddenCases()));
    }

    private List<AdminProblemUpsertRequest.StarterCodeRequest> normalizeStarterCodes(
            List<AdminProblemUpsertRequest.StarterCodeRequest> starterCodes) {
        if (starterCodes == null) {
            return null;
        }
        return starterCodes.stream()
                .map(starterCode -> new AdminProblemUpsertRequest.StarterCodeRequest(
                        starterCode.language(),
                        normalizeEscapedNewlineText(starterCode.code()),
                        starterCode.isDefault()))
                .toList();
    }

    private List<AdminProblemUpsertRequest.TestCaseRequest> normalizeTestCases(
            List<AdminProblemUpsertRequest.TestCaseRequest> testCases) {
        if (testCases == null) {
            return null;
        }
        return testCases.stream()
                .map(testCase -> new AdminProblemUpsertRequest.TestCaseRequest(
                        normalizeEscapedNewlineText(testCase.input()), normalizeEscapedNewlineText(testCase.output())))
                .toList();
    }

    private String normalizeEscapedNewlineText(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\r\n", "\n").replace("\\r\\n", "\n").replace("\\n", "\n");
    }

    private String normalizeRequired(String value, String message) {
        if (isBlank(value)) {
            throw new ServiceException("400-1", message);
        }
        return value.trim();
    }

    private String normalizeOptional(String value) {
        if (isBlank(value)) {
            return null;
        }
        return value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
