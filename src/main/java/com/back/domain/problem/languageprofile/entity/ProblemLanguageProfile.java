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
            // 문제 1개당 동일 언어 profile이 중복 생성되지 않도록 강제
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
    // 어떤 문제의 언어 profile인지 식별
    private Problem problem;

    @Column(name = "language_code", nullable = false, length = 30)
    // 예: python3, java, c, cpp17, javascript
    private String languageCode;

    @Column(name = "starter_code", columnDefinition = "TEXT", nullable = false)
    // 해당 언어 선택 시 에디터 초기 코드
    private String starterCode;

    @Column(name = "is_default", nullable = false)
    // 상세 화면 기본 선택 언어 여부
    private Boolean isDefault;

    public static ProblemLanguageProfile create(
            Problem problem, String languageCode, String starterCode, Boolean isDefault) {
        ProblemLanguageProfile profile = new ProblemLanguageProfile();
        profile.problem = problem;
        profile.languageCode = languageCode;
        profile.starterCode = starterCode;
        profile.isDefault = Boolean.TRUE.equals(isDefault);
        return profile;
    }
}
