package com.back.domain.problem.problem.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.back.domain.problem.problem.entity.Problem;

public interface ProblemRepository extends JpaRepository<Problem, Long> {}
