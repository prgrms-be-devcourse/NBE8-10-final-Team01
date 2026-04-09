package com.back.domain.problem.problem.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.problem.problem.enums.DifficultyLevel;
import com.back.domain.problem.problem.enums.InputMode;
import com.back.domain.problem.problem.enums.JudgeType;

public interface ProblemRepository extends JpaRepository<Problem, Long> {
    Optional<Problem> findBySourceProblemId(String sourceProblemId);

    @Query("""
            select p.id
            from Problem p
            where p.title = :title
              and p.content = :content
              and p.difficulty = :difficulty
              and p.difficultyRating = :difficultyRating
              and p.timeLimitMs = :timeLimitMs
              and p.memoryLimitMb = :memoryLimitMb
              and p.inputMode = :inputMode
              and p.judgeType = :judgeType
              and ((p.inputFormat is null and :inputFormat is null) or p.inputFormat = :inputFormat)
              and ((p.outputFormat is null and :outputFormat is null) or p.outputFormat = :outputFormat)
              and ((p.checkerCode is null and :checkerCode is null) or p.checkerCode = :checkerCode)
            """)
    java.util.List<Long> findIdsBySignature(
            @Param("title") String title,
            @Param("content") String content,
            @Param("difficulty") DifficultyLevel difficulty,
            @Param("difficultyRating") Integer difficultyRating,
            @Param("timeLimitMs") Long timeLimitMs,
            @Param("memoryLimitMb") Long memoryLimitMb,
            @Param("inputMode") InputMode inputMode,
            @Param("judgeType") JudgeType judgeType,
            @Param("inputFormat") String inputFormat,
            @Param("outputFormat") String outputFormat,
            @Param("checkerCode") String checkerCode);
}
