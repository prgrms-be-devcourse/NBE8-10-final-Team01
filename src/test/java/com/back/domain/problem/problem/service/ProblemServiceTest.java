package com.back.domain.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.back.domain.problem.problem.dto.ProblemDetailResponse;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.problem.problem.enums.DifficultyLevel;
import com.back.domain.problem.problem.repository.ProblemRepository;
import com.back.global.exception.ServiceException;

class ProblemServiceTest {

    private final ProblemRepository problemRepository = mock(ProblemRepository.class);
    private final ProblemService problemService = new ProblemService(problemRepository);

    @Test
    @DisplayName("문제 ID로 단건 조회 시 프론트 전달용 상세 정보를 반환한다")
    void getProblem_returnsProblemDetail() {
        // given
        Problem problem = mock(Problem.class);
        when(problem.getId()).thenReturn(1L);
        when(problem.getTitle()).thenReturn("A + B");
        when(problem.getDifficulty()).thenReturn(DifficultyLevel.EASY);
        when(problem.getContent()).thenReturn("두 정수 A, B를 입력받아 합을 출력하시오.");
        when(problem.getInputFormat()).thenReturn("첫 줄에 A, B가 공백으로 주어진다.");
        when(problem.getOutputFormat()).thenReturn("A+B를 출력한다.");
        when(problem.getTimeLimitMs()).thenReturn(1000L);
        when(problem.getMemoryLimitMb()).thenReturn(256L);

        when(problemRepository.findById(1L)).thenReturn(Optional.of(problem));

        // when
        ProblemDetailResponse response = problemService.getProblem(1L);

        // then
        assertThat(response.problemId()).isEqualTo(1L);
        assertThat(response.title()).isEqualTo("A + B");
        assertThat(response.difficulty()).isEqualTo("EASY");
        assertThat(response.content()).isEqualTo("두 정수 A, B를 입력받아 합을 출력하시오.");
        assertThat(response.inputFormat()).isEqualTo("첫 줄에 A, B가 공백으로 주어진다.");
        assertThat(response.outputFormat()).isEqualTo("A+B를 출력한다.");
        assertThat(response.timeLimitMs()).isEqualTo(1000L);
        assertThat(response.memoryLimitMb()).isEqualTo(256L);
    }

    @Test
    @DisplayName("존재하지 않는 문제 ID 조회 시 예외를 던진다")
    void getProblem_throws_whenProblemNotFound() {
        // given
        when(problemRepository.findById(999L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> problemService.getProblem(999L))
                .isInstanceOf(ServiceException.class)
                .hasMessage("404-1 : 존재하지 않는 문제입니다.");
    }
}
