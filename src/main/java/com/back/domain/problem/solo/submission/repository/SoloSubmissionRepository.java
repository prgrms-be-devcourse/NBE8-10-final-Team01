package com.back.domain.problem.solo.submission.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.back.domain.problem.solo.submission.entity.SoloSubmission;
import com.back.domain.problem.submission.entity.SubmissionResult;

public interface SoloSubmissionRepository extends JpaRepository<SoloSubmission, Long> {

    // 채점 후 연관된 member/problem을 즉시 사용해야 해서 fetch join 조회를 별도로 둔다.
    @Query("select s from SoloSubmission s join fetch s.member join fetch s.problem where s.id = :id")
    Optional<SoloSubmission> findWithMemberAndProblemById(@Param("id") Long id);

    // 잔디 히트맵용: 같은 문제를 여러 번 AC해도 최초 AC 시각 1건만 집계한다.
    @Query("""
            select min(s.createdAt)
            from SoloSubmission s
            where s.member.id = :memberId
              and s.result = :result
            group by s.problem.id
            """)
    List<LocalDateTime> findFirstAcTimesByMemberIdAndResult(
            @Param("memberId") Long memberId, @Param("result") SubmissionResult result);
}
