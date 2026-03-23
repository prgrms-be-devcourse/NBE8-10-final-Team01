package com.back.domain.problem.submission.entity;

import com.back.domain.battle.battleroom.entity.BattleRoom;
import com.back.domain.member.member.entity.Member;
import com.back.global.jpa.entity.BaseEntity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "submissions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Submission extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "submission_seq_gen")
    @SequenceGenerator(name = "submission_seq_gen", sequenceName = "submission_id_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "room_id")
    private BattleRoom battleRoom;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private Member member;

    @Column(columnDefinition = "TEXT")
    private String code;

    private String language;

    @Enumerated(EnumType.STRING)
    private SubmissionResult result;

    private Integer passedCount;
    private Integer totalCount;

    public static Submission create(BattleRoom battleRoom, Member member, String code, String language) {
        Submission submission = new Submission();
        submission.battleRoom = battleRoom;
        submission.member = member;
        submission.code = code;
        submission.language = language;
        return submission;
    }

    public void applyJudgeResult(SubmissionResult result, int passedCount, int totalCount) {
        this.result = result;
        this.passedCount = passedCount;
        this.totalCount = totalCount;
    }
}
