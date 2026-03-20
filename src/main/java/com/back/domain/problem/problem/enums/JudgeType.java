package com.back.domain.problem.problem.enums;

/**
 * 채점 판정 방식.
 * EXACT: 정답 문자열 직접 비교, CHECKER: 커스텀 checker 코드 실행.
 */
public enum JudgeType {
    EXACT,
    CHECKER
}
