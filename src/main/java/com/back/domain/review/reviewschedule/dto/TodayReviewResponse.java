package com.back.domain.review.reviewschedule.dto;

import java.util.List;

import com.back.domain.review.reviewschedule.entity.ReviewSchedule;

public record TodayReviewResponse(List<ReviewItem> reviews) {

    public record ReviewItem(Long problemId, String problemTitle, int reviewCount) {
        public static ReviewItem from(ReviewSchedule schedule) {
            return new ReviewItem(
                    schedule.getProblem().getId(), schedule.getProblem().getTitle(), schedule.getReviewCount());
        }
    }
}
