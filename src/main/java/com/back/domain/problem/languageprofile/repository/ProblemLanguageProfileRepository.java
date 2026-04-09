package com.back.domain.problem.languageprofile.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.back.domain.problem.languageprofile.entity.ProblemLanguageProfile;

public interface ProblemLanguageProfileRepository extends JpaRepository<ProblemLanguageProfile, Long> {
    // 상세 응답 구성 시 문제별 언어 profile 전체를 안정적인 순서(id 오름차순)로 조회
    List<ProblemLanguageProfile> findByProblemIdOrderByIdAsc(Long problemId);

    void deleteByProblemId(Long problemId);
}
