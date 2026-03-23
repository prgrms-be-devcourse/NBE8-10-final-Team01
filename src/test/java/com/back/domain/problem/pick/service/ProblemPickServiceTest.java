package com.back.domain.problem.pick.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.back.domain.problem.pick.dto.ProblemPickRequest;
import com.back.domain.problem.pick.repository.ProblemPickQueryRepository;
import com.back.domain.problem.problem.enums.DifficultyLevel;

class ProblemPickServiceTest {

    private final ProblemPickQueryRepository queryRepository = mock(ProblemPickQueryRepository.class);
    private final ProblemPickService problemPickService = new ProblemPickService(queryRepository);

    @Test
    @DisplayName("조건에 맞는 후보가 있으면 problemId를 반환한다")
    void pickProblemId_returnsProblemId_whenCandidateExists() {
        // given
        ProblemPickRequest request =
                new ProblemPickRequest(" Array ", DifficultyLevel.EASY, List.of(1L, 2L), List.of(10L));

        when(queryRepository.countCandidates(DifficultyLevel.EASY, "array", List.of(10L)))
                .thenReturn(1L);
        when(queryRepository.findCandidateIdByOffset(DifficultyLevel.EASY, "array", List.of(10L), 0))
                .thenReturn(Optional.of(42L));

        // when
        Long problemId = problemPickService.pickProblemId(request);

        // then
        assertThat(problemId).isEqualTo(42L);
        verify(queryRepository).countCandidates(DifficultyLevel.EASY, "array", List.of(10L));
        verify(queryRepository).findCandidateIdByOffset(DifficultyLevel.EASY, "array", List.of(10L), 0);
    }

    @Test
    @DisplayName("큐 연동용 단축 메서드로도 problemId를 반환할 수 있다")
    void pickProblemId_shortcutMethod_returnsProblemId() {
        // given
        when(queryRepository.countCandidates(DifficultyLevel.EASY, "array", List.of()))
                .thenReturn(1L);
        when(queryRepository.findCandidateIdByOffset(DifficultyLevel.EASY, "array", List.of(), 0))
                .thenReturn(Optional.of(7L));

        // when
        Long problemId = problemPickService.pickProblemId("Array", DifficultyLevel.EASY, List.of(1L, 2L, 3L, 4L));

        // then
        assertThat(problemId).isEqualTo(7L);
        verify(queryRepository).countCandidates(DifficultyLevel.EASY, "array", List.of());
        verify(queryRepository).findCandidateIdByOffset(DifficultyLevel.EASY, "array", List.of(), 0);
    }

    @Test
    @DisplayName("제외 목록으로 후보가 없으면 제외 없이 1회 재시도한다")
    void pickProblemId_retriesWithoutExclude_whenExcludedCandidatesAreEmpty() {
        // given
        ProblemPickRequest request =
                new ProblemPickRequest("Graph", DifficultyLevel.MEDIUM, List.of(1L, 2L), List.of(11L, 12L));

        when(queryRepository.countCandidates(DifficultyLevel.MEDIUM, "graph", List.of(11L, 12L)))
                .thenReturn(0L);
        when(queryRepository.countCandidates(DifficultyLevel.MEDIUM, "graph", List.of()))
                .thenReturn(1L);
        when(queryRepository.findCandidateIdByOffset(eq(DifficultyLevel.MEDIUM), eq("graph"), eq(List.of()), anyInt()))
                .thenReturn(Optional.of(99L));

        // when
        Long problemId = problemPickService.pickProblemId(request);

        // then
        assertThat(problemId).isEqualTo(99L);
        verify(queryRepository).countCandidates(DifficultyLevel.MEDIUM, "graph", List.of(11L, 12L));
        verify(queryRepository).countCandidates(DifficultyLevel.MEDIUM, "graph", List.of());
        verify(queryRepository)
                .findCandidateIdByOffset(eq(DifficultyLevel.MEDIUM), eq("graph"), eq(List.of()), anyInt());
    }

    @Test
    @DisplayName("재시도 후에도 후보가 없으면 예외를 던진다")
    void pickProblemId_throws_whenNoCandidateAfterRetry() {
        // given
        ProblemPickRequest request = new ProblemPickRequest("DP", DifficultyLevel.HARD, List.of(1L), List.of(100L));

        when(queryRepository.countCandidates(DifficultyLevel.HARD, "dp", List.of(100L)))
                .thenReturn(0L);
        when(queryRepository.countCandidates(DifficultyLevel.HARD, "dp", List.of()))
                .thenReturn(0L);

        // when & then
        assertThatThrownBy(() -> problemPickService.pickProblemId(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("출제 가능한 문제가 없습니다.");
    }

    @Test
    @DisplayName("난이도/카테고리가 없으면 쿼리 없이 예외를 던진다")
    void pickProblemId_throwsValidationError_whenRequiredFieldsMissing() {
        // given
        ProblemPickRequest noDifficulty = new ProblemPickRequest("Array", null, List.of(), List.of());
        ProblemPickRequest noCategory = new ProblemPickRequest(" ", DifficultyLevel.EASY, List.of(), List.of());

        // when & then
        assertThatThrownBy(() -> problemPickService.pickProblemId(noDifficulty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("난이도는 필수입니다.");

        assertThatThrownBy(() -> problemPickService.pickProblemId(noCategory))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("카테고리는 필수입니다.");

        verifyNoInteractions(queryRepository);
    }

    @Test
    @DisplayName("후보 수가 int 범위를 넘으면 명확한 예외를 던진다")
    void pickProblemId_throws_whenCandidateCountExceedsIntRange() {
        // given
        ProblemPickRequest request = new ProblemPickRequest("Array", DifficultyLevel.EASY, List.of(), List.of());

        when(queryRepository.countCandidates(DifficultyLevel.EASY, "array", List.of()))
                .thenReturn((long) Integer.MAX_VALUE + 1);

        // when & then
        assertThatThrownBy(() -> problemPickService.pickProblemId(request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("출제 후보 수가 너무 커 오프셋 계산이 불가능합니다.");
    }
}
