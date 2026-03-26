package com.back.domain.problem.problem.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.back.domain.problem.languageprofile.entity.ProblemLanguageProfile;
import com.back.domain.problem.languageprofile.repository.ProblemLanguageProfileRepository;
import com.back.domain.problem.problem.dto.ProblemDetailResponse;
import com.back.domain.problem.problem.dto.ProblemListResponse;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.problem.problem.repository.ProblemRepository;
import com.back.domain.problem.testcase.entity.TestCase;
import com.back.global.exception.ServiceException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProblemService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ProblemRepository problemRepository;
    private final ProblemLanguageProfileRepository problemLanguageProfileRepository;

    public ProblemListResponse getProblems(int page, int size) {
        if (page < 0) {
            throw new ServiceException("400-2", "page는 0 이상이어야 합니다.");
        }
        if (size <= 0 || size > MAX_PAGE_SIZE) {
            throw new ServiceException("400-3", "size는 1 이상 100 이하여야 합니다.");
        }

        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "id"));
        Page<Problem> problemPage = problemRepository.findAll(pageable);

        return ProblemListResponse.from(problemPage);
    }

    public ProblemDetailResponse getProblem(Long problemId) {
        if (problemId == null) {
            throw new ServiceException("400-1", "문제 ID는 필수입니다.");
        }

        Problem problem = problemRepository
                .findById(problemId)
                .orElseThrow(() -> new ServiceException("404-1", "존재하지 않는 문제입니다."));

        List<ProblemLanguageProfile> languageProfiles =
                problemLanguageProfileRepository.findByProblemIdOrderByIdAsc(problemId);

        List<String> supportedLanguages = languageProfiles.stream()
                .map(ProblemLanguageProfile::getLanguageCode)
                .distinct()
                .toList();

        String defaultLanguage = languageProfiles.stream()
                .filter(profile -> Boolean.TRUE.equals(profile.getIsDefault()))
                .findFirst()
                .map(ProblemLanguageProfile::getLanguageCode)
                .or(() -> languageProfiles.stream().findFirst().map(ProblemLanguageProfile::getLanguageCode))
                .orElse(null);

        List<ProblemDetailResponse.StarterCode> starterCodes = languageProfiles.stream()
                .map(profile ->
                        new ProblemDetailResponse.StarterCode(profile.getLanguageCode(), profile.getStarterCode()))
                .toList();

        List<ProblemDetailResponse.SampleCase> sampleCases = problem.getTestCases().stream()
                .filter(tc -> Boolean.TRUE.equals(tc.getIsSample()))
                .map(this::toSampleCase)
                .toList();

        return ProblemDetailResponse.from(problem, supportedLanguages, defaultLanguage, starterCodes, sampleCases);
    }

    private ProblemDetailResponse.SampleCase toSampleCase(TestCase testCase) {
        return new ProblemDetailResponse.SampleCase(testCase.getInput(), testCase.getExpectedOutput());
    }
}
