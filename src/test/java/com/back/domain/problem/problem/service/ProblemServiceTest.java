package com.back.domain.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.back.domain.problem.languageprofile.entity.ProblemLanguageProfile;
import com.back.domain.problem.languageprofile.repository.ProblemLanguageProfileRepository;
import com.back.domain.problem.problem.dto.ProblemDetailResponse;
import com.back.domain.problem.problem.dto.ProblemListResponse;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.problem.problem.enums.DifficultyLevel;
import com.back.domain.problem.problem.repository.ProblemRepository;
import com.back.domain.problem.testcase.entity.TestCase;
import com.back.domain.problem.translation.entity.ProblemTranslation;
import com.back.domain.problem.translation.repository.ProblemTranslationRepository;
import com.back.global.exception.ServiceException;

class ProblemServiceTest {

    private final ProblemRepository problemRepository = mock(ProblemRepository.class);
    private final ProblemLanguageProfileRepository problemLanguageProfileRepository =
            mock(ProblemLanguageProfileRepository.class);
    private final ProblemTranslationRepository problemTranslationRepository = mock(ProblemTranslationRepository.class);

    private final ProblemService problemService =
            new ProblemService(problemRepository, problemLanguageProfileRepository, problemTranslationRepository);

    @Test
    @DisplayName("문제 목록 조회 시 페이지 정보와 요약 목록을 반환한다")
    void getProblems_returnsPagedSummary() {
        // given
        Problem problem = mock(Problem.class);
        when(problem.getId()).thenReturn(1L);
        when(problem.getTitle()).thenReturn("A + B");
        when(problem.getDifficulty()).thenReturn(DifficultyLevel.EASY);
        when(problem.getDifficultyRating()).thenReturn(800);
        when(problem.getTimeLimitMs()).thenReturn(1000L);
        when(problem.getMemoryLimitMb()).thenReturn(256L);

        Pageable pageable = PageRequest.of(0, 20);
        Page<Problem> page = new PageImpl<>(List.of(problem), pageable, 1);
        when(problemRepository.findAll(any(Pageable.class))).thenReturn(page);

        // when
        ProblemListResponse response = problemService.getProblems(0, 20);

        // then
        assertThat(response.problems()).hasSize(1);
        assertThat(response.problems().get(0).problemId()).isEqualTo(1L);
        assertThat(response.problems().get(0).title()).isEqualTo("A + B");
        assertThat(response.problems().get(0).difficulty()).isEqualTo("EASY");
        assertThat(response.problems().get(0).difficultyRating()).isEqualTo(800);
        assertThat(response.pageInfo().page()).isEqualTo(0);
        assertThat(response.pageInfo().size()).isEqualTo(20);
        assertThat(response.pageInfo().totalElements()).isEqualTo(1L);
        assertThat(response.pageInfo().totalPages()).isEqualTo(1);
        assertThat(response.pageInfo().hasNext()).isFalse();
    }

    @Test
    @DisplayName("문제 목록 조회 시 page가 음수면 예외를 던진다")
    void getProblems_throws_whenPageIsNegative() {
        assertThatThrownBy(() -> problemService.getProblems(-1, 20))
                .isInstanceOf(ServiceException.class)
                .hasMessage("400-2 : page는 0 이상이어야 합니다.");
    }

    @Test
    @DisplayName("문제 목록 조회 시 size가 1~100 범위를 벗어나면 예외를 던진다")
    void getProblems_throws_whenSizeIsOutOfRange() {
        assertThatThrownBy(() -> problemService.getProblems(0, 0))
                .isInstanceOf(ServiceException.class)
                .hasMessage("400-3 : size는 1 이상 100 이하여야 합니다.");

        assertThatThrownBy(() -> problemService.getProblems(0, 101))
                .isInstanceOf(ServiceException.class)
                .hasMessage("400-3 : size는 1 이상 100 이하여야 합니다.");
    }

    @Test
    @DisplayName("문제 ID로 단건 조회 시 lang이 없으면 원문 상세 정보를 반환한다")
    void getProblem_withoutLang_returnsOriginalProblemDetail() {
        // given
        Problem problem = mock(Problem.class);
        TestCase sampleCase = mock(TestCase.class);
        TestCase hiddenCase = mock(TestCase.class);
        ProblemLanguageProfile pythonProfile = mock(ProblemLanguageProfile.class);
        ProblemLanguageProfile javaProfile = mock(ProblemLanguageProfile.class);

        when(problem.getId()).thenReturn(1L);
        when(problem.getTitle()).thenReturn("A + B");
        when(problem.getDifficulty()).thenReturn(DifficultyLevel.EASY);
        when(problem.getContent()).thenReturn("두 정수 A, B를 입력받아 합을 출력하시오.");
        when(problem.getInputFormat()).thenReturn("첫 줄에 A, B가 공백으로 주어진다.");
        when(problem.getOutputFormat()).thenReturn("A+B를 출력한다.");
        when(problem.getTimeLimitMs()).thenReturn(1000L);
        when(problem.getMemoryLimitMb()).thenReturn(256L);
        when(problem.getTestCases()).thenReturn(List.of(sampleCase, hiddenCase));

        when(sampleCase.getIsSample()).thenReturn(true);
        when(sampleCase.getInput()).thenReturn("1 2");
        when(sampleCase.getExpectedOutput()).thenReturn("3");

        when(hiddenCase.getIsSample()).thenReturn(false);

        when(pythonProfile.getLanguageCode()).thenReturn("python3");
        when(pythonProfile.getStarterCode()).thenReturn("print('hello')");
        when(pythonProfile.getIsDefault()).thenReturn(true);

        when(javaProfile.getLanguageCode()).thenReturn("java");
        when(javaProfile.getStarterCode()).thenReturn("class Main {}");
        when(javaProfile.getIsDefault()).thenReturn(false);

        when(problemRepository.findById(1L)).thenReturn(Optional.of(problem));
        when(problemLanguageProfileRepository.findByProblemIdOrderByIdAsc(1L))
                .thenReturn(List.of(pythonProfile, javaProfile));

        // when
        ProblemDetailResponse response = problemService.getProblem(1L, null);

        // then
        assertThat(response.problemId()).isEqualTo(1L);
        assertThat(response.title()).isEqualTo("A + B");
        assertThat(response.difficulty()).isEqualTo("EASY");
        assertThat(response.content()).isEqualTo("두 정수 A, B를 입력받아 합을 출력하시오.");
        assertThat(response.inputFormat()).isEqualTo("첫 줄에 A, B가 공백으로 주어진다.");
        assertThat(response.outputFormat()).isEqualTo("A+B를 출력한다.");
        assertThat(response.timeLimitMs()).isEqualTo(1000L);
        assertThat(response.memoryLimitMb()).isEqualTo(256L);
        assertThat(response.language()).isEqualTo("en");
        assertThat(response.supportedLanguages()).containsExactly("python3", "java");
        assertThat(response.defaultLanguage()).isEqualTo("python3");
        assertThat(response.starterCodes())
                .containsExactly(
                        new ProblemDetailResponse.StarterCode("python3", "print('hello')"),
                        new ProblemDetailResponse.StarterCode("java", "class Main {}"));
        assertThat(response.sampleCases()).containsExactly(new ProblemDetailResponse.SampleCase("1 2", "3"));
    }

    @Test
    @DisplayName("문제 ID로 단건 조회 시 lang이 ko이고 번역이 있으면 번역본을 반환한다")
    void getProblem_withKoAndTranslation_returnsTranslatedDetail() {
        // given
        Problem problem = mock(Problem.class);
        ProblemTranslation translation = mock(ProblemTranslation.class);
        TestCase sampleCase = mock(TestCase.class);
        ProblemLanguageProfile pythonProfile = mock(ProblemLanguageProfile.class);

        when(problem.getId()).thenReturn(1L);
        when(problem.getTitle()).thenReturn("A + B");
        when(problem.getDifficulty()).thenReturn(DifficultyLevel.EASY);
        when(problem.getContent()).thenReturn("Solve A + B.");
        when(problem.getInputFormat()).thenReturn("Input A and B.");
        when(problem.getOutputFormat()).thenReturn("Print A + B.");
        when(problem.getTimeLimitMs()).thenReturn(1000L);
        when(problem.getMemoryLimitMb()).thenReturn(256L);
        when(problem.getTestCases()).thenReturn(List.of(sampleCase));

        when(sampleCase.getIsSample()).thenReturn(true);
        when(sampleCase.getInput()).thenReturn("1 2");
        when(sampleCase.getExpectedOutput()).thenReturn("3");

        when(pythonProfile.getLanguageCode()).thenReturn("python3");
        when(pythonProfile.getStarterCode()).thenReturn("print('hello')");
        when(pythonProfile.getIsDefault()).thenReturn(true);

        when(translation.getTitle()).thenReturn("A + B");
        when(translation.getContent()).thenReturn("두 정수 A와 B의 합을 구하세요.");
        when(translation.getInputFormat()).thenReturn("한 줄에 A와 B가 주어집니다.");
        when(translation.getOutputFormat()).thenReturn("A+B 값을 출력합니다.");

        when(problemRepository.findById(1L)).thenReturn(Optional.of(problem));
        when(problemLanguageProfileRepository.findByProblemIdOrderByIdAsc(1L)).thenReturn(List.of(pythonProfile));
        when(problemTranslationRepository.findByProblem_IdAndLanguageCode(1L, "ko"))
                .thenReturn(Optional.of(translation));

        // when
        ProblemDetailResponse response = problemService.getProblem(1L, "ko");

        // then
        assertThat(response.problemId()).isEqualTo(1L);
        assertThat(response.title()).isEqualTo("A + B");
        assertThat(response.content()).isEqualTo("두 정수 A와 B의 합을 구하세요.");
        assertThat(response.inputFormat()).isEqualTo("한 줄에 A와 B가 주어집니다.");
        assertThat(response.outputFormat()).isEqualTo("A+B 값을 출력합니다.");
        assertThat(response.language()).isEqualTo("ko");
        assertThat(response.supportedLanguages()).containsExactly("python3");
        assertThat(response.defaultLanguage()).isEqualTo("python3");
    }

    @Test
    @DisplayName("문제 ID로 단건 조회 시 lang이 ko여도 번역이 없으면 원문을 반환한다")
    void getProblem_withKoWithoutTranslation_returnsOriginalDetail() {
        // given
        Problem problem = mock(Problem.class);
        ProblemLanguageProfile pythonProfile = mock(ProblemLanguageProfile.class);

        when(problem.getId()).thenReturn(1L);
        when(problem.getTitle()).thenReturn("A + B");
        when(problem.getDifficulty()).thenReturn(DifficultyLevel.EASY);
        when(problem.getContent()).thenReturn("Solve A + B.");
        when(problem.getInputFormat()).thenReturn("Input A and B.");
        when(problem.getOutputFormat()).thenReturn("Print A + B.");
        when(problem.getTimeLimitMs()).thenReturn(1000L);
        when(problem.getMemoryLimitMb()).thenReturn(256L);
        when(problem.getTestCases()).thenReturn(List.of());

        when(pythonProfile.getLanguageCode()).thenReturn("python3");
        when(pythonProfile.getStarterCode()).thenReturn("print('hello')");
        when(pythonProfile.getIsDefault()).thenReturn(true);

        when(problemRepository.findById(1L)).thenReturn(Optional.of(problem));
        when(problemLanguageProfileRepository.findByProblemIdOrderByIdAsc(1L)).thenReturn(List.of(pythonProfile));
        when(problemTranslationRepository.findByProblem_IdAndLanguageCode(1L, "ko"))
                .thenReturn(Optional.empty());

        // when
        ProblemDetailResponse response = problemService.getProblem(1L, "ko");

        // then
        assertThat(response.problemId()).isEqualTo(1L);
        assertThat(response.title()).isEqualTo("A + B");
        assertThat(response.content()).isEqualTo("Solve A + B.");
        assertThat(response.inputFormat()).isEqualTo("Input A and B.");
        assertThat(response.outputFormat()).isEqualTo("Print A + B.");
        assertThat(response.language()).isEqualTo("en");
    }

    @Test
    @DisplayName("존재하지 않는 문제 ID 조회 시 예외를 던진다")
    void getProblem_throws_whenProblemNotFound() {
        // given
        when(problemRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> problemService.getProblem(999L, null))
                .isInstanceOf(ServiceException.class)
                .hasMessage("404-1 : 존재하지 않는 문제입니다.");
    }
}
