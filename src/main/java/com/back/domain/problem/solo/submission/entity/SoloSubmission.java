package com.back.domain.problem.solo.submission.entity;

import com.back.domain.member.member.entity.Member;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.problem.submission.entity.SubmissionResult;
import com.back.global.jpa.entity.BaseEntity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "solo_submissions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SoloSubmission extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "solo_submission_seq_gen")
    @SequenceGenerator(name = "solo_submission_seq_gen", sequenceName = "solo_submission_id_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id")
    private Member member;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id")
    private Problem problem;

    @Column(columnDefinition = "TEXT")
    private String code;

    private String language;

    @Enumerated(EnumType.STRING)
    private SubmissionResult result;

    private Integer passedCount;
    private Integer totalCount;

    public static SoloSubmission create(Member member, Problem problem, String code, String language) {
        SoloSubmission s = new SoloSubmission();
        s.member = member;
        s.problem = problem;
        s.code = code;
        s.language = language;
        return s;
    }

    public void applyJudgeResult(SubmissionResult result, int passedCount, int totalCount) {
        this.result = result;
        this.passedCount = passedCount;
        this.totalCount = totalCount;
    }
}
