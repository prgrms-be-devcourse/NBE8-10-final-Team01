package com.back.domain.problem.translation.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.back.domain.problem.translation.entity.ProblemTranslation;

public interface ProblemTranslationRepository extends JpaRepository<ProblemTranslation, Long> {

    Optional<ProblemTranslation> findByProblem_IdAndLanguageCode(Long problemId, String languageCode);
}
