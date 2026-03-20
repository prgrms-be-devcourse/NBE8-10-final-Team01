package com.back.domain.problem.problem.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.back.domain.problem.problem.enums.DifficultyLevel;

/**
 * rating -> EASY/MEDIUM/HARD 변환 규칙이 깨지지 않도록 경계값 중심으로 검증한다.
 */
class ProblemDifficultyMapperTest {

    @Test
    void mapsNullAndLowRatingToEasy() {
        assertThat(ProblemDifficultyMapper.toLevel(null)).isEqualTo(DifficultyLevel.EASY);
        assertThat(ProblemDifficultyMapper.toLevel(799)).isEqualTo(DifficultyLevel.EASY);
    }

    @Test
    void mapsRatingToThreeLevelDifficulty() {
        assertThat(ProblemDifficultyMapper.toLevel(800)).isEqualTo(DifficultyLevel.EASY);
        assertThat(ProblemDifficultyMapper.toLevel(1399)).isEqualTo(DifficultyLevel.EASY);
        assertThat(ProblemDifficultyMapper.toLevel(1400)).isEqualTo(DifficultyLevel.MEDIUM);
        assertThat(ProblemDifficultyMapper.toLevel(2000)).isEqualTo(DifficultyLevel.MEDIUM);
        assertThat(ProblemDifficultyMapper.toLevel(2199)).isEqualTo(DifficultyLevel.MEDIUM);
        assertThat(ProblemDifficultyMapper.toLevel(2200)).isEqualTo(DifficultyLevel.HARD);
        assertThat(ProblemDifficultyMapper.toLevel(3500)).isEqualTo(DifficultyLevel.HARD);
    }

    @Test
    void exposesDifficultyString() {
        assertThat(ProblemDifficultyMapper.toDifficulty(1200)).isEqualTo("EASY");
        assertThat(ProblemDifficultyMapper.toDifficulty(1700)).isEqualTo("MEDIUM");
        assertThat(ProblemDifficultyMapper.toDifficulty(2500)).isEqualTo("HARD");
    }
}
