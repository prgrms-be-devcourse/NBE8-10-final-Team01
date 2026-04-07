package com.back.domain.problem.problem.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.back.domain.problem.problem.dto.ProblemDetailResponse;
import com.back.domain.problem.problem.dto.ProblemListResponse;
import com.back.domain.problem.problem.dto.ProblemSummaryResponse;
import com.back.domain.problem.problem.service.ProblemService;

class ProblemControllerTest {

    private final ProblemService problemService = mock(ProblemService.class);
    private final ProblemController problemController = new ProblemController(problemService);

    @Test
    @DisplayName("문제 목록 조회 컨트롤러는 서비스 결과를 그대로 반환한다")
    void getProblems_returnsServiceResult() {
        // given
        ProblemListResponse response = new ProblemListResponse(
                List.of(new ProblemSummaryResponse(1L, "A + B", "EASY", 800, 1000L, 256L)),
                new ProblemListResponse.PageInfo(0, 20, 1L, 1, false));
        when(problemService.getProblems(0, 20)).thenReturn(response);

        // when
        ProblemListResponse actual = problemController.getProblems(0, 20);

        // then
        assertThat(actual).isEqualTo(response);
        verify(problemService).getProblems(0, 20);
    }

    @Test
    @DisplayName("문제 단건 조회 컨트롤러는 서비스 결과를 그대로 반환한다")
    void getProblem_returnsServiceResult() {
        // given
        ProblemDetailResponse response = new ProblemDetailResponse(
                1L,
                "A + B",
                "EASY",
                "두 정수 A, B를 입력받아 합을 출력하시오.",
                "첫 줄에 A, B가 공백으로 주어진다.",
                "A+B를 출력한다.",
                1000L,
                256L,
                "ko",
                List.of("python3", "java"),
                "python3",
                List.of(
                        new ProblemDetailResponse.StarterCode("python3", "print('hello')"),
                        new ProblemDetailResponse.StarterCode("java", "class Main {}")),
                List.of(new ProblemDetailResponse.SampleCase("1 2", "3"))
        );

        when(problemService.getProblem(1L, "ko")).thenReturn(response);

        // when
        ProblemDetailResponse actual = problemController.getProblem(1L, "ko");

        // then
        assertThat(actual).isEqualTo(response);
        verify(problemService).getProblem(1L, "ko");
    }

    @Test
    @DisplayName("문제 단건 조회 시 lang이 없으면 null을 서비스에 전달한다")
    void getProblem_withoutLang_passesNullToService() {
        // given
        ProblemDetailResponse response = new ProblemDetailResponse(
                1L,
                "A + B",
                "EASY",
                "You are given two integers A and B.",
                "The first line contains A and B.",
                "Print A+B.",
                1000L,
                256L,
                "en",
                List.of("python3", "java"),
                "python3",
                List.of(
                        new ProblemDetailResponse.StarterCode("python3", "print('hello')"),
                        new ProblemDetailResponse.StarterCode("java", "class Main {}")
                ),
                List.of(new ProblemDetailResponse.SampleCase("1 2", "3"))
        );

        when(problemService.getProblem(1L, null)).thenReturn(response);

        // when
        ProblemDetailResponse actual = problemController.getProblem(1L, null);

        // then
        assertThat(actual).isEqualTo(response);
        verify(problemService).getProblem(1L, null);
    }
}
