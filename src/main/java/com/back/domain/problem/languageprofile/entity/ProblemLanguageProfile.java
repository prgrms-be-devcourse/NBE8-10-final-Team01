package com.back.domain.problem.languageprofile.entity;

import com.back.domain.problem.problem.entity.Problem;
import com.back.global.jpa.entity.BaseEntity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "problem_language_profiles",
        uniqueConstraints = {
            @UniqueConstraint(
                    name = "uk_problem_language_profiles_problem_language",
                    columnNames = {"problem_id", "language_code"})
        })
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProblemLanguageProfile extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "problem_language_profile_seq_gen")
    @SequenceGenerator(
            name = "problem_language_profile_seq_gen",
            sequenceName = "problem_language_profile_id_seq",
            allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "problem_id", nullable = false)
    private Problem problem;

    @Column(name = "language_code", nullable = false, length = 30)
    private String languageCode;

    @Column(name = "starter_code", columnDefinition = "TEXT", nullable = false)
    private String starterCode;

    @Column(name = "is_default", nullable = false)
    private Boolean isDefault;
}
