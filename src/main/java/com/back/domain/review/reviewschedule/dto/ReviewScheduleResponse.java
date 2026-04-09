package com.back.domain.review.reviewschedule.dto;

import com.back.domain.review.reviewschedule.entity.ReviewSchedule;

public record ReviewScheduleResponse(int reviewCount, boolean isReviewRequired) {

    public static ReviewScheduleResponse from(ReviewSchedule schedule) {
        return new ReviewScheduleResponse(schedule.getReviewCount(), schedule.isReviewRequired());
    }
}
