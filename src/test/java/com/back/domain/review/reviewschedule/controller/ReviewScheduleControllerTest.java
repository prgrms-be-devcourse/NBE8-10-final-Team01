package com.back.domain.review.reviewschedule.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.back.domain.member.member.entity.Member;
import com.back.domain.review.reviewschedule.dto.TodayReviewResponse;
import com.back.domain.review.reviewschedule.service.ReviewScheduleService;
import com.back.global.rq.Rq;
import com.back.global.rsData.RsData;

class ReviewScheduleControllerTest {

    private final ReviewScheduleService reviewScheduleService = mock(ReviewScheduleService.class);
    private final Rq rq = mock(Rq.class);
    private final ReviewScheduleController controller = new ReviewScheduleController(reviewScheduleService, rq);

    @Test
    @DisplayName("비로그인 상태에서 요청하면 401을 반환한다")
    void getTodayReviews_unauthenticated_returns401() {
        when(rq.getActor()).thenReturn(null);

        RsData<TodayReviewResponse> result = controller.getTodayReviews();

        assertThat(result.resultCode()).isEqualTo("401");
        verify(reviewScheduleService, never()).getTodayReviews(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("로그인 상태에서 요청하면 서비스 결과와 함께 200을 반환한다")
    void getTodayReviews_authenticated_returns200WithItems() {
        Member actor = Member.of(1L, "test@test.com", "tester");
        TodayReviewResponse.ReviewItem item = new TodayReviewResponse.ReviewItem(10L, "문제A", 1);
        TodayReviewResponse serviceResponse = new TodayReviewResponse(List.of(item));

        when(rq.getActor()).thenReturn(actor);
        when(reviewScheduleService.getTodayReviews(1L)).thenReturn(serviceResponse);

        RsData<TodayReviewResponse> result = controller.getTodayReviews();

        assertThat(result.resultCode()).isEqualTo("200");
        assertThat(result.data().reviews()).hasSize(1);
        assertThat(result.data().reviews().get(0).problemId()).isEqualTo(10L);
        assertThat(result.data().reviews().get(0).problemTitle()).isEqualTo("문제A");
        assertThat(result.data().reviews().get(0).reviewCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("오늘 복습할 문제가 없으면 빈 목록과 함께 200을 반환한다")
    void getTodayReviews_authenticated_noItems_returns200WithEmptyList() {
        Member actor = Member.of(2L, "user@test.com", "user");
        TodayReviewResponse serviceResponse = new TodayReviewResponse(List.of());

        when(rq.getActor()).thenReturn(actor);
        when(reviewScheduleService.getTodayReviews(2L)).thenReturn(serviceResponse);

        RsData<TodayReviewResponse> result = controller.getTodayReviews();

        assertThat(result.resultCode()).isEqualTo("200");
        assertThat(result.data().reviews()).isEmpty();
    }

    @Test
    @DisplayName("비로그인 상태에서 dismiss 요청하면 401을 반환한다")
    void dismissReview_unauthenticated_returns401() {
        when(rq.getActor()).thenReturn(null);

        RsData<Void> result = controller.dismissReview(10L);

        assertThat(result.resultCode()).isEqualTo("401");
        verify(reviewScheduleService, never())
                .dismissReview(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("로그인 상태에서 dismiss 요청하면 서비스를 호출하고 200을 반환한다")
    void dismissReview_authenticated_returns200() {
        Member actor = Member.of(1L, "test@test.com", "tester");
        when(rq.getActor()).thenReturn(actor);
        doNothing().when(reviewScheduleService).dismissReview(10L, 1L);

        RsData<Void> result = controller.dismissReview(10L);

        assertThat(result.resultCode()).isEqualTo("200");
        verify(reviewScheduleService).dismissReview(10L, 1L);
    }
}
