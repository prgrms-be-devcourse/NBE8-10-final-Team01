package com.back.domain.problem.problem.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.back.domain.problem.problem.dto.ProblemDetailResponse;
import com.back.domain.problem.problem.service.ProblemService;

class ProblemControllerTest {

    private final ProblemService problemService = mock(ProblemService.class);
    private final ProblemController problemController = new ProblemController(problemService);

    @Test
    @DisplayName("문제 단건 조회 컨트롤러는 서비스 결과를 그대로 반환한다")
    void getProblem_returnsServiceResult() {
        // given
        ProblemDetailResponse response = new ProblemDetailResponse(
                1L, "A + B", "EASY", "두 정수 A, B를 입력받아 합을 출력하시오.", "첫 줄에 A, B가 공백으로 주어진다.", "A+B를 출력한다.", 1000L, 256L);
        when(problemService.getProblem(1L)).thenReturn(response);

        // when
        ProblemDetailResponse actual = problemController.getProblem(1L);

        // then
        assertThat(actual).isEqualTo(response);
        verify(problemService).getProblem(1L);
    }
}
