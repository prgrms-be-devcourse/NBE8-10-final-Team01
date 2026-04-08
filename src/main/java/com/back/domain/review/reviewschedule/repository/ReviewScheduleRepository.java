package com.back.domain.review.reviewschedule.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.back.domain.member.member.entity.Member;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.review.reviewschedule.entity.ReviewSchedule;

public interface ReviewScheduleRepository extends JpaRepository<ReviewSchedule, Long> {

    Optional<ReviewSchedule> findByMemberAndProblem(Member member, Problem problem);

    @Query("SELECT rs FROM ReviewSchedule rs JOIN FETCH rs.problem "
            + "WHERE rs.member.id = :memberId "
            + "AND rs.isReviewRequired = true "
            + "AND rs.nextReviewAt <= :now")
    List<ReviewSchedule> findTodayReviews(@Param("memberId") Long memberId, @Param("now") LocalDateTime now);
}
