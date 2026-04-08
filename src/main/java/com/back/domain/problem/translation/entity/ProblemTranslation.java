package com.back.domain.problem.translation.entity;

import java.time.OffsetDateTime;

import com.back.domain.problem.problem.entity.Problem;
import com.back.global.jpa.entity.BaseEntity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "problem_translations",
        uniqueConstraints = {@UniqueConstraint(columnNames = {"problem_id", "language_code"})})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProblemTranslation extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id", nullable = false)
    private Problem problem;

    @Column(name = "language_code", nullable = false, length = 10)
    private String languageCode;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    @Column(name = "input_format", columnDefinition = "TEXT")
    private String inputFormat;

    @Column(name = "output_format", columnDefinition = "TEXT")
    private String outputFormat;

    @Column(name = "source_hash", length = 64)
    private String sourceHash;

    @Column(name = "provider", length = 50)
    private String provider;

    @Column(name = "translated_at", nullable = false)
    private OffsetDateTime translatedAt;
}
