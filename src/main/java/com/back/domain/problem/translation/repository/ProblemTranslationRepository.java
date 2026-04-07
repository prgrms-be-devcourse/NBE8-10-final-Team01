package com.back.domain.problem.translation.repository;

import java.util.Optional;

import com.back.domain.problem.translation.entity.ProblemTranslation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProblemTranslationRepository extends JpaRepository<ProblemTranslation, Long> {

    Optional<ProblemTranslation> findByProblem_IdAndLanguageCode(Long problemId, String languageCode);
}