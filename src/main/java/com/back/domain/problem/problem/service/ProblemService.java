package com.back.domain.problem.problem.service;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.back.domain.problem.problem.dto.ProblemDetailResponse;
import com.back.domain.problem.problem.dto.ProblemListResponse;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.problem.problem.repository.ProblemRepository;
import com.back.global.exception.ServiceException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProblemService {

    private static final int MAX_PAGE_SIZE = 100;

    private final ProblemRepository problemRepository;

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

        return ProblemDetailResponse.from(problem);
    }
}
