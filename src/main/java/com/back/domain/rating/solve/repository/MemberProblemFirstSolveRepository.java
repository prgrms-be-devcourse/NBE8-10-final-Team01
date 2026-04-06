package com.back.domain.rating.solve.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.back.domain.rating.solve.entity.MemberProblemFirstSolve;

public interface MemberProblemFirstSolveRepository extends JpaRepository<MemberProblemFirstSolve, Long> {

    // 이미 first-AC가 기록된 문제인지 확인해 중복 보상 지급을 막는다.
    boolean existsByMemberIdAndProblemId(Long memberId, Long problemId);

    // 사용자의 고유 해결 수(문제 수) 집계
    long countByMemberId(Long memberId);

    // 고난도(예: 2200+) 해결 수 게이트 계산용
    long countByMemberIdAndProblemDifficultyRatingGreaterThanEqual(Long memberId, Integer problemDifficultyRating);
}
