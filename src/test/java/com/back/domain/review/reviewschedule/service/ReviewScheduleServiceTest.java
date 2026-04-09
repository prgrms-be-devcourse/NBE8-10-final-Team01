package com.back.domain.review.reviewschedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.server.ResponseStatusException;

import com.back.domain.member.member.entity.Member;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.review.reviewschedule.dto.TodayReviewResponse;
import com.back.domain.review.reviewschedule.entity.ReviewSchedule;
import com.back.domain.review.reviewschedule.repository.ReviewScheduleRepository;

class ReviewScheduleServiceTest {

    private final ReviewScheduleRepository reviewScheduleRepository = mock(ReviewScheduleRepository.class);
    private final ReviewScheduleService reviewScheduleService = new ReviewScheduleService(reviewScheduleRepository);

    private final Member member = mock(Member.class);
    private final Problem problem = mock(Problem.class);
    private final LocalDateTime solvedAt = LocalDateTime.of(2026, 4, 9, 12, 0);

    @Test
    @DisplayName("첫 AC 시 reviewCount=1, nextReviewAt=+3일로 복습 스케줄을 신규 생성한다")
    void firstAc_createsNewSchedule() {
        when(reviewScheduleRepository.findByMemberAndProblem(member, problem)).thenReturn(Optional.empty());

        reviewScheduleService.recordAcResult(member, problem, solvedAt);

        ArgumentCaptor<ReviewSchedule> captor = forClass(ReviewSchedule.class);
        verify(reviewScheduleRepository).save(captor.capture());
        ReviewSchedule saved = captor.getValue();

        assertThat(saved.getReviewCount()).isEqualTo(1);
        assertThat(saved.getSolvedAt()).isEqualTo(solvedAt);
        assertThat(saved.getNextReviewAt()).isEqualTo(solvedAt.plusDays(3));
        assertThat(saved.isReviewRequired()).isTrue();
    }

    @Test
    @DisplayName("두 번째 AC 시 nextReviewAt을 7일 후로 갱신한다")
    void secondAc_updatesNextReviewTo7Days() {
        ReviewSchedule existing = ReviewSchedule.of(member, problem, solvedAt.minusDays(3), solvedAt);
        when(reviewScheduleRepository.findByMemberAndProblem(member, problem)).thenReturn(Optional.of(existing));

        reviewScheduleService.recordAcResult(member, problem, solvedAt);

        assertThat(existing.getReviewCount()).isEqualTo(2);
        assertThat(existing.getSolvedAt()).isEqualTo(solvedAt);
        assertThat(existing.getNextReviewAt()).isEqualTo(solvedAt.plusDays(7));
        assertThat(existing.isReviewRequired()).isTrue();
        verify(reviewScheduleRepository, never()).save(any());
    }

    @Test
    @DisplayName("세 번째 AC 시 nextReviewAt을 30일 후로 갱신한다")
    void thirdAc_updatesNextReviewTo30Days() {
        ReviewSchedule existing = scheduleWithReviewCount(2);
        when(reviewScheduleRepository.findByMemberAndProblem(member, problem)).thenReturn(Optional.of(existing));

        reviewScheduleService.recordAcResult(member, problem, solvedAt);

        assertThat(existing.getReviewCount()).isEqualTo(3);
        assertThat(existing.getNextReviewAt()).isEqualTo(solvedAt.plusDays(30));
        assertThat(existing.isReviewRequired()).isTrue();
    }

    @Test
    @DisplayName("네 번째 AC 시 nextReviewAt을 180일 후로 갱신한다")
    void fourthAc_updatesNextReviewTo180Days() {
        ReviewSchedule existing = scheduleWithReviewCount(3);
        when(reviewScheduleRepository.findByMemberAndProblem(member, problem)).thenReturn(Optional.of(existing));

        reviewScheduleService.recordAcResult(member, problem, solvedAt);

        assertThat(existing.getReviewCount()).isEqualTo(4);
        assertThat(existing.getNextReviewAt()).isEqualTo(solvedAt.plusDays(180));
        assertThat(existing.isReviewRequired()).isTrue();
    }

    @Test
    @DisplayName("다섯 번째 AC 이후 모든 복습을 완료하면 isReviewRequired를 false로 설정한다")
    void fifthAcOrMore_setsReviewRequiredFalse() {
        ReviewSchedule existing = scheduleWithReviewCount(4);
        when(reviewScheduleRepository.findByMemberAndProblem(member, problem)).thenReturn(Optional.of(existing));

        reviewScheduleService.recordAcResult(member, problem, solvedAt);

        assertThat(existing.getReviewCount()).isEqualTo(5);
        assertThat(existing.isReviewRequired()).isFalse();
    }

    @Test
    @DisplayName("오늘 복습할 스케줄이 있으면 ReviewItem 목록을 반환한다")
    void getTodayReviews_returnsItems() {
        Problem p1 = mock(Problem.class);
        Problem p2 = mock(Problem.class);
        when(p1.getId()).thenReturn(10L);
        when(p1.getTitle()).thenReturn("문제A");
        when(p2.getId()).thenReturn(20L);
        when(p2.getTitle()).thenReturn("문제B");

        Member m1 = mock(Member.class);
        Member m2 = mock(Member.class);
        ReviewSchedule s1 = ReviewSchedule.of(m1, p1, solvedAt, solvedAt.plusDays(3));
        ReviewSchedule s2 = ReviewSchedule.of(m2, p2, solvedAt, solvedAt.plusDays(3));
        s2.updateOnAc(solvedAt, solvedAt.plusDays(7), true);

        when(reviewScheduleRepository.findTodayReviews(any(), any())).thenReturn(List.of(s1, s2));

        TodayReviewResponse response = reviewScheduleService.getTodayReviews(99L);

        assertThat(response.totalCount()).isEqualTo(2);
        assertThat(response.reviews()).hasSize(2);
        assertThat(response.reviews().get(0).problemId()).isEqualTo(10L);
        assertThat(response.reviews().get(0).problemTitle()).isEqualTo("문제A");
        assertThat(response.reviews().get(0).reviewCount()).isEqualTo(1);
        assertThat(response.reviews().get(1).problemId()).isEqualTo(20L);
        assertThat(response.reviews().get(1).reviewCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("오늘 복습할 스케줄이 없으면 빈 목록을 반환한다")
    void getTodayReviews_returnsEmptyList() {
        when(reviewScheduleRepository.findTodayReviews(any(), any())).thenReturn(List.of());

        TodayReviewResponse response = reviewScheduleService.getTodayReviews(1L);

        assertThat(response.reviews()).isEmpty();
    }

    @Test
    @DisplayName("dismissReview 호출 시 isReviewRequired가 false로 변경된다")
    void dismissReview_setsReviewRequiredFalse() {
        ReviewSchedule schedule = scheduleWithReviewCount(1);
        when(reviewScheduleRepository.findByMemberIdAndProblemId(1L, 10L)).thenReturn(Optional.of(schedule));

        reviewScheduleService.dismissReview(10L, 1L);

        assertThat(schedule.isReviewRequired()).isFalse();
    }

    @Test
    @DisplayName("존재하지 않는 스케줄을 dismiss하면 예외가 발생한다")
    void dismissReview_notFound_throwsException() {
        when(reviewScheduleRepository.findByMemberIdAndProblemId(1L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reviewScheduleService.dismissReview(10L, 1L))
                .isInstanceOf(ResponseStatusException.class);
    }

    /**
     * reviewCount가 targetCount인 ReviewSchedule을 생성한다.
     * ReviewSchedule.of()는 reviewCount=1로 생성되고,
     * updateOnAc() 호출마다 reviewCount가 1씩 증가한다.
     */
    private ReviewSchedule scheduleWithReviewCount(int targetCount) {
        ReviewSchedule schedule = ReviewSchedule.of(member, problem, solvedAt, solvedAt.plusDays(3));
        for (int i = 1; i < targetCount; i++) {
            schedule.updateOnAc(solvedAt, solvedAt.plusDays(1), true);
        }
        return schedule;
    }
}
