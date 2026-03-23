package com.back.domain.problem.problem.service;

import java.util.NoSuchElementException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.back.domain.problem.problem.dto.ProblemDetailResponse;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.problem.problem.repository.ProblemRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProblemService {

    private final ProblemRepository problemRepository;

    public ProblemDetailResponse getProblem(Long problemId) {
        if (problemId == null) {
            throw new IllegalArgumentException("문제 ID는 필수입니다.");
        }

        Problem problem =
                problemRepository.findById(problemId).orElseThrow(() -> new NoSuchElementException("존재하지 않는 문제입니다."));

        return ProblemDetailResponse.from(problem);
    }
}
