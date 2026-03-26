package com.back.domain.problem.languageprofile.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.back.domain.problem.languageprofile.entity.ProblemLanguageProfile;

public interface ProblemLanguageProfileRepository extends JpaRepository<ProblemLanguageProfile, Long> {
    List<ProblemLanguageProfile> findByProblemIdOrderByIdAsc(Long problemId);
}
