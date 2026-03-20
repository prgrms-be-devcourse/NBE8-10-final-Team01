package com.back.domain.problem.problem.entity;

import java.util.ArrayList;
import java.util.List;

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

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String difficulty;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(nullable = false)
    private Long timeLimitMs;

    @Column(nullable = false)
    private Long memoryLimitMb;

    @OneToMany(mappedBy = "problem")
    private List<TestCase> testCases = new ArrayList<>();
}
