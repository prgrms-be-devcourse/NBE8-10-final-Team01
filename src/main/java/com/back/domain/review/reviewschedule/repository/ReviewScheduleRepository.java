package com.back.domain.review.reviewschedule.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.back.domain.member.member.entity.Member;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.review.reviewschedule.entity.ReviewSchedule;

public interface ReviewScheduleRepository extends JpaRepository<ReviewSchedule, Long> {
    Optional<ReviewSchedule> findByMemberAndProblem(Member member, Problem problem);
}
