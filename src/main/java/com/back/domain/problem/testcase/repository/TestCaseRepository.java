package com.back.domain.problem.testcase.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.back.domain.problem.testcase.entity.TestCase;

public interface TestCaseRepository extends JpaRepository<TestCase, Long> {
    void deleteByProblemId(Long problemId);
}
