package com.back.domain.problem.testcase.entity;

import com.back.domain.problem.problem.entity.Problem;
import com.back.global.jpa.entity.BaseEntity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "test_cases")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TestCase extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "test_case_seq_gen")
    @SequenceGenerator(name = "test_case_seq_gen", sequenceName = "test_case_id_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id")
    private Problem problem;

    @Column(columnDefinition = "TEXT")
    private String input;

    @Column(columnDefinition = "TEXT")
    private String expectedOutput;

    private Boolean isSample; // 예제 케이스 여부

    public static TestCase create(Problem problem, String input, String expectedOutput, Boolean isSample) {
        TestCase testCase = new TestCase();
        testCase.problem = problem;
        testCase.input = input;
        testCase.expectedOutput = expectedOutput;
        testCase.isSample = isSample;
        return testCase;
    }
}
