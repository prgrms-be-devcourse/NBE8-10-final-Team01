package com.back.domain.review.reviewschedule.dto;

import java.util.List;

import com.back.domain.problem.problem.enums.DifficultyLevel;
import com.back.domain.review.reviewschedule.entity.ReviewSchedule;

public record TodayReviewResponse(int totalCount, List<ReviewItem> reviews) {

    public TodayReviewResponse(List<ReviewItem> reviews) {
        this(reviews.size(), reviews);
    }

    public record ReviewItem(
            Long problemId,
            String problemTitle,
            DifficultyLevel difficulty,
            Integer difficultyRating,
            Long timeLimitMs,
            Long memoryLimitMb,
            int reviewCount) {

        public static ReviewItem from(ReviewSchedule schedule) {
            var problem = schedule.getProblem();
            return new ReviewItem(
                    problem.getId(),
                    problem.getTitle(),
                    problem.getDifficulty(),
                    problem.getDifficultyRating(),
                    problem.getTimeLimitMs(),
                    problem.getMemoryLimitMb(),
                    schedule.getReviewCount());
        }
    }
}
