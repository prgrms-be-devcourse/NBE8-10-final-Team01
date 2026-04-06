package com.back.domain.rating.profile.entity;

import com.back.domain.member.member.entity.Member;
import com.back.global.jpa.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "member_rating_profiles",
        uniqueConstraints = @UniqueConstraint(name = "uq_member_rating_profiles_member_id", columnNames = "member_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberRatingProfile extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "member_rating_profile_seq_gen")
    @SequenceGenerator(
            name = "member_rating_profile_seq_gen",
            sequenceName = "member_rating_profile_id_seq",
            allocationSize = 50)
    private Long id;

    @OneToOne(optional = false)
    @JoinColumn(name = "member_id", nullable = false, unique = true)
    private Member member;

    // 경쟁 실력 지표(SR)
    @Column(name = "battle_rating")
    private Integer battleRating;

    // 하드 배틀(난이도 2000+) 전용 실력 지표(Hard SR)
    @Column(name = "hard_battle_rating")
    private Integer hardBattleRating;

    // 활동 점수(AP): 문제별 first-AC 난이도 누적 지표(솔로/배틀 공통)
    @Column(name = "first_solve_score")
    private Integer firstSolveScore;

    // 리더보드 표시에 사용하는 통합 점수(현재는 SR 중심)
    @Column(name = "tier_score")
    private Integer tierScore;

    // 배치 구간(K=64) 여부 판단용 누적 배틀 판수
    @Column(name = "battle_match_count")
    private Integer battleMatchCount;

    // 문제별 first-AC(솔로/배틀 포함) 기준 누적 해결 문제 수
    @Column(name = "first_solved_problem_count")
    private Integer firstSolvedProblemCount;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", length = 20)
    private RatingTier tier;

    public static MemberRatingProfile createDefault(Member member) {
        MemberRatingProfile ranking = new MemberRatingProfile();
        ranking.member = member;
        ranking.battleRating = 1000;
        ranking.hardBattleRating = 1000;
        ranking.firstSolveScore = 0;
        ranking.tierScore = 1000;
        ranking.battleMatchCount = 0;
        ranking.firstSolvedProblemCount = 0;
        ranking.tier = RatingTier.BRONZE_5;
        return ranking;
    }

    // null 안전 누적으로 SR 증감을 반영한다.
    public void applyBattleRatingDelta(int delta) {
        this.battleRating = (this.battleRating == null ? 1000 : this.battleRating) + delta;
    }

    // null 안전 누적으로 Hard SR 증감을 반영한다.
    public void applyHardBattleRatingDelta(int delta) {
        this.hardBattleRating = (this.hardBattleRating == null ? 1000 : this.hardBattleRating) + delta;
    }

    // null 안전 누적으로 문제별 first-AC 난이도 점수를 반영한다.
    public void applyFirstSolveScoreDelta(int delta) {
        this.firstSolveScore = (this.firstSolveScore == null ? 0 : this.firstSolveScore) + delta;
    }

    // 배틀/솔로 입력값으로 계산된 통합 점수를 덮어쓴다.
    public void updateTierScore(int tierScore) {
        this.tierScore = tierScore;
    }

    // TierPolicy로 계산한 티어를 반영한다.
    public void updateTier(RatingTier tier) {
        this.tier = tier;
    }

    // null 안전 카운팅으로 랭크업 최소 활동량 조건 계산에 사용한다.
    public void increaseBattleMatchCount() {
        this.battleMatchCount = (this.battleMatchCount == null ? 0 : this.battleMatchCount) + 1;
    }

    // 문제별 첫 해결 이벤트(솔로/배틀)가 확정된 경우에만 증가시킨다.
    public void increaseFirstSolvedProblemCount() {
        this.firstSolvedProblemCount = (this.firstSolvedProblemCount == null ? 0 : this.firstSolvedProblemCount) + 1;
    }
}
