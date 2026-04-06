package com.back.domain.rating.solve.entity;

import java.time.LocalDateTime;

import com.back.domain.member.member.entity.Member;
import com.back.domain.problem.problem.entity.Problem;
import com.back.global.jpa.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "member_problem_first_solves",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_member_problem_first_solve",
                        columnNames = {"member_id", "problem_id"}),
        indexes = {
            @Index(name = "idx_mpfs_member_id", columnList = "member_id"),
            @Index(name = "idx_mpfs_first_solved_at", columnList = "first_solved_at")
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberProblemFirstSolve extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "member_problem_first_solve_seq_gen")
    @SequenceGenerator(
            name = "member_problem_first_solve_seq_gen",
            sequenceName = "member_problem_first_solve_id_seq",
            allocationSize = 50)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @ManyToOne(optional = false)
    @JoinColumn(name = "problem_id", nullable = false)
    private Problem problem;

    // 같은 문제를 처음 해결한 모드(솔로/배틀)
    @Enumerated(EnumType.STRING)
    @Column(name = "first_solved_mode", nullable = false, length = 20)
    private FirstSolvedMode firstSolvedMode;

    // first-AC 시점의 문제 난이도 rating snapshot
    @Column(name = "problem_difficulty_rating")
    private Integer problemDifficultyRating;

    @Column(name = "first_solved_at", nullable = false)
    private LocalDateTime firstSolvedAt;

    // member_id + problem_id 유니크 제약으로 중복 first-AC 적재를 방지한다.
    public static MemberProblemFirstSolve create(
            Member member,
            Problem problem,
            FirstSolvedMode firstSolvedMode,
            Integer problemDifficultyRating,
            LocalDateTime firstSolvedAt) {
        MemberProblemFirstSolve firstSolve = new MemberProblemFirstSolve();
        firstSolve.member = member;
        firstSolve.problem = problem;
        firstSolve.firstSolvedMode = firstSolvedMode;
        firstSolve.problemDifficultyRating = problemDifficultyRating;
        firstSolve.firstSolvedAt = firstSolvedAt;
        return firstSolve;
    }
}
