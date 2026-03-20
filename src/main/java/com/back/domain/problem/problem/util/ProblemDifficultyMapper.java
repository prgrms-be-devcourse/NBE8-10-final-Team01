package com.back.domain.problem.problem.util;

import com.back.domain.problem.problem.enums.DifficultyLevel;

/**
 * open-r1 Codeforces rating을 서비스 3단계 난이도(EASY/MEDIUM/HARD)로 변환한다.
 */
public final class ProblemDifficultyMapper {
    private ProblemDifficultyMapper() {}

    /**
     * rating 숫자 -> 3단계 난이도 enum.
     * 기준:
     * - EASY: 0~1399 및 null
     * - MEDIUM: 1400~2199
     * - HARD: 2200+
     */
    public static DifficultyLevel toLevel(Integer rating) {
        if (rating == null || rating < 1400) {
            return DifficultyLevel.EASY;
        }
        if (rating < 2200) {
            return DifficultyLevel.MEDIUM;
        }
        return DifficultyLevel.HARD;
    }

    /**
     * DB difficulty 컬럼에 바로 넣기 위한 문자열 변환.
     */
    public static String toDifficulty(Integer rating) {
        return toLevel(rating).name();
    }
}
