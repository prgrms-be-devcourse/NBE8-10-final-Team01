package com.back.domain.review.reviewschedule.service;

import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.back.domain.member.member.entity.Member;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.review.reviewschedule.entity.ReviewSchedule;
import com.back.domain.review.reviewschedule.repository.ReviewScheduleRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ReviewScheduleService {

    private final ReviewScheduleRepository reviewScheduleRepository;

    /**
     * AC 결과를 복습 스케줄에 반영한다.
     * - 기존 스케줄이 없으면 신규 생성 (reviewCount=1, nextReviewAt=+3일)
     * - 기존 스케줄이 있으면 reviewCount를 증가시키고 다음 복습일을 갱신한다.
     * - reviewCount가 4를 초과하면 isReviewRequired=false로 설정한다.
     */
    @Transactional
    public void recordAcResult(Member member, Problem problem, LocalDateTime solvedAt) {
        reviewScheduleRepository.findByMemberAndProblem(member, problem)
                .ifPresentOrElse(
                        schedule -> {
                            int nextCount = schedule.getReviewCount() + 1;
                            boolean isReviewRequired = nextCount <= 4;
                            LocalDateTime nextReviewAt = isReviewRequired
                                    ? calcNextReviewAt(solvedAt, nextCount)
                                    : solvedAt;
                            schedule.updateOnAc(solvedAt, nextReviewAt, isReviewRequired);
                        },
                        () -> reviewScheduleRepository.save(
                                ReviewSchedule.of(member, problem, solvedAt, solvedAt.plusDays(3)))
                );
    }

    /**
     * 복습 간격 (스페이스 리피티션)
     * reviewCount=1 → +3일
     * reviewCount=2 → +7일
     * reviewCount=3 → +30일
     * reviewCount=4 → +180일
     */
    private LocalDateTime calcNextReviewAt(LocalDateTime base, int reviewCount) {
        return switch (reviewCount) {
            case 1 -> base.plusDays(3);
            case 2 -> base.plusDays(7);
            case 3 -> base.plusDays(30);
            case 4 -> base.plusDays(180);
            default -> base;
        };
    }
}
