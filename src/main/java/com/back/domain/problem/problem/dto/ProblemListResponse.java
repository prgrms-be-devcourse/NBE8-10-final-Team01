package com.back.domain.problem.problem.dto;

import java.util.List;

import org.springframework.data.domain.Page;

import com.back.domain.problem.problem.entity.Problem;

public record ProblemListResponse(List<ProblemSummaryResponse> problems, PageInfo pageInfo) {

    public record PageInfo(int page, int size, long totalElements, int totalPages, boolean hasNext) {
        public static PageInfo from(Page<?> page) {
            return new PageInfo(
                    page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages(), page.hasNext());
        }
    }

    public static ProblemListResponse from(Page<Problem> problemPage) {
        List<ProblemSummaryResponse> items = problemPage.getContent().stream()
                .map(ProblemSummaryResponse::from)
                .toList();

        return new ProblemListResponse(items, PageInfo.from(problemPage));
    }
}
