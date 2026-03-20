package com.back.domain.problem.problem.entity;

import java.util.ArrayList;
import java.util.List;

import com.back.domain.problem.problem.enums.DifficultyLevel;
import com.back.domain.problem.problem.enums.InputMode;
import com.back.domain.problem.problem.enums.JudgeType;
import com.back.domain.problem.testcase.entity.TestCase;
import com.back.global.jpa.entity.BaseEntity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "problems")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Problem extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "problem_seq_gen")
    @SequenceGenerator(name = "problem_seq_gen", sequenceName = "problem_id_seq", allocationSize = 50)
    private Long id;

    @Column(name = "source_problem_id", unique = true, length = 50)
    // 외부 원본의 안정 식별자(예: Codeforces 852/A).
    private String sourceProblemId;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DifficultyLevel difficulty;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "difficulty_rating")
    // 원본 rating 값을 그대로 저장해서 필터/정렬에 사용한다.
    private Integer difficultyRating;

    private Long timeLimitMs;

    @Column(nullable = false)
    private Long memoryLimitMb;

    @Column(name = "input_format", columnDefinition = "TEXT")
    // 문제 입력 형식 원문을 별도 저장한다.
    private String inputFormat;

    @Column(name = "output_format", columnDefinition = "TEXT")
    // 문제 출력 형식 원문을 별도 저장한다.
    private String outputFormat;

    @Enumerated(EnumType.STRING)
    @Column(name = "input_mode", nullable = false, length = 10)
    // 문제의 채점 입출력 방식(STDIO/FILE).
    private InputMode inputMode = InputMode.STDIO;

    @Column(name = "checker_code", columnDefinition = "TEXT")
    // 다중 정답 문제에 대한 checker 코드. 없으면 EXACT 비교를 사용한다.
    private String checkerCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "judge_type", nullable = false, length = 20)
    // EXACT(문자열 비교) 또는 CHECKER(커스텀 checker 실행).
    private JudgeType judgeType = JudgeType.EXACT;

    @OneToMany(mappedBy = "problem")
    private List<TestCase> testCases = new ArrayList<>();
}
