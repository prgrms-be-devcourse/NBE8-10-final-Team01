package com.back.domain.review.reviewschedule.entity;

import java.time.LocalDateTime;

import com.back.domain.member.member.entity.Member;
import com.back.domain.problem.problem.entity.Problem;
import com.back.global.jpa.entity.BaseEntity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "review_schedule")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ReviewSchedule extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "review_schedule_seq_gen")
    @SequenceGenerator(name = "review_schedule_seq_gen", sequenceName = "review_schedule_id_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id")
    private Problem problem;

    private LocalDateTime solvedAt;
    private LocalDateTime nextReviewAt;
    private Integer reviewCount;

    @Column(nullable = false)
    private boolean isReviewRequired = true;

    public static ReviewSchedule of(Member member, Problem problem,
            LocalDateTime solvedAt, LocalDateTime nextReviewAt) {
        ReviewSchedule schedule = new ReviewSchedule();
        schedule.member = member;
        schedule.problem = problem;
        schedule.solvedAt = solvedAt;
        schedule.nextReviewAt = nextReviewAt;
        schedule.reviewCount = 1;
        schedule.isReviewRequired = true;
        return schedule;
    }

    public void updateOnAc(LocalDateTime solvedAt, LocalDateTime nextReviewAt, boolean isReviewRequired) {
        this.solvedAt = solvedAt;
        this.nextReviewAt = nextReviewAt;
        this.reviewCount = this.reviewCount + 1;
        this.isReviewRequired = isReviewRequired;
    }
}
