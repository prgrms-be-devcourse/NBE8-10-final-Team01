package com.back.domain.problem.solo.submission.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.back.domain.problem.solo.submission.entity.SoloSubmission;

public interface SoloSubmissionRepository extends JpaRepository<SoloSubmission, Long> {

    // 채점 후 연관된 member/problem을 즉시 사용해야 해서 fetch join 조회를 별도로 둔다.
    @Query("select s from SoloSubmission s join fetch s.member join fetch s.problem where s.id = :id")
    Optional<SoloSubmission> findWithMemberAndProblemById(@Param("id") Long id);
}
