package com.back.domain.review.reviewschedule.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.back.domain.member.member.entity.Member;
import com.back.domain.review.reviewschedule.dto.TodayReviewResponse;
import com.back.domain.review.reviewschedule.service.ReviewScheduleService;
import com.back.global.rq.Rq;
import com.back.global.rsData.RsData;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/review")
@RequiredArgsConstructor
public class ReviewScheduleController {

    private final ReviewScheduleService reviewScheduleService;
    private final Rq rq;

    @GetMapping("/today")
    public RsData<TodayReviewResponse> getTodayReviews() {
        Member actor = rq.getActor();
        if (actor == null) {
            return RsData.of("401", "로그인이 필요합니다.");
        }
        TodayReviewResponse response = reviewScheduleService.getTodayReviews(actor.getId());
        return RsData.of("200", "오늘의 복습 문제를 조회했습니다.", response);
    }

    @PatchMapping("/dismiss")
    public RsData<Void> dismissReview(@RequestParam Long problemId) {
        Member actor = rq.getActor();
        if (actor == null) {
            return RsData.of("401", "로그인이 필요합니다.");
        }
        reviewScheduleService.dismissReview(problemId, actor.getId());
        return RsData.of("200", "해당 문제를 복습 목록에서 제외했습니다.");
    }
}
