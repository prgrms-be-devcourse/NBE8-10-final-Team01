package com.back.domain.review.reviewschedule.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.back.domain.member.member.entity.Member;
import com.back.domain.problem.problem.entity.Problem;
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
        when(reviewScheduleRepository.findByMemberAndProblem(member, problem))
                .thenReturn(Optional.empty());

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
        when(reviewScheduleRepository.findByMemberAndProblem(member, problem))
                .thenReturn(Optional.of(existing));

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
        when(reviewScheduleRepository.findByMemberAndProblem(member, problem))
                .thenReturn(Optional.of(existing));

        reviewScheduleService.recordAcResult(member, problem, solvedAt);

        assertThat(existing.getReviewCount()).isEqualTo(3);
        assertThat(existing.getNextReviewAt()).isEqualTo(solvedAt.plusDays(30));
        assertThat(existing.isReviewRequired()).isTrue();
    }

    @Test
    @DisplayName("네 번째 AC 시 nextReviewAt을 180일 후로 갱신한다")
    void fourthAc_updatesNextReviewTo180Days() {
        ReviewSchedule existing = scheduleWithReviewCount(3);
        when(reviewScheduleRepository.findByMemberAndProblem(member, problem))
                .thenReturn(Optional.of(existing));

        reviewScheduleService.recordAcResult(member, problem, solvedAt);

        assertThat(existing.getReviewCount()).isEqualTo(4);
        assertThat(existing.getNextReviewAt()).isEqualTo(solvedAt.plusDays(180));
        assertThat(existing.isReviewRequired()).isTrue();
    }

    @Test
    @DisplayName("다섯 번째 AC 이후 모든 복습을 완료하면 isReviewRequired를 false로 설정한다")
    void fifthAcOrMore_setsReviewRequiredFalse() {
        ReviewSchedule existing = scheduleWithReviewCount(4);
        when(reviewScheduleRepository.findByMemberAndProblem(member, problem))
                .thenReturn(Optional.of(existing));

        reviewScheduleService.recordAcResult(member, problem, solvedAt);

        assertThat(existing.getReviewCount()).isEqualTo(5);
        assertThat(existing.isReviewRequired()).isFalse();
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
