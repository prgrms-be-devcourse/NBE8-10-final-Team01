package com.back.domain.tag.problemtagconnect.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.back.domain.tag.problemtagconnect.entity.ProblemTagConnect;

public interface ProblemTagConnectRepository extends JpaRepository<ProblemTagConnect, Long> {
    void deleteByProblemId(Long problemId);
}
