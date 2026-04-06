package com.back.domain.rating.profile.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.back.domain.rating.profile.entity.MemberRatingProfile;

public interface MemberRatingProfileRepository extends JpaRepository<MemberRatingProfile, Long> {

    Optional<MemberRatingProfile> findByMemberId(Long memberId);

    // 통합 리더보드: tier score 내림차순, 동점 시 member id 오름차순
    Page<MemberRatingProfile> findAllByOrderByTierScoreDescMemberIdAsc(Pageable pageable);

    // 배틀 전용 리더보드
    Page<MemberRatingProfile> findAllByOrderByBattleRatingDescMemberIdAsc(Pageable pageable);

    // 문제별 first-AC 난이도 누적 리더보드
    Page<MemberRatingProfile> findAllByOrderByFirstSolveScoreDescMemberIdAsc(Pageable pageable);
}
