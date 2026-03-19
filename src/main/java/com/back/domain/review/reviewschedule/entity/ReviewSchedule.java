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

    private LocalDateTime solvedAt; // 마지막으로 푼 날짜
    private LocalDateTime nextReviewAt; // 다음 복습 예정일
    private Integer reviewCount; // 복습 횟수
}
