package com.back.domain.rating.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.back.domain.member.member.entity.Member;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.rating.profile.entity.MemberRatingProfile;
import com.back.domain.rating.profile.repository.MemberRatingProfileRepository;
import com.back.domain.rating.solve.entity.MemberProblemFirstSolve;
import com.back.domain.rating.solve.repository.MemberProblemFirstSolveRepository;

class RatingProfileServiceTest {

    private final MemberRatingProfileRepository memberRatingProfileRepository =
            mock(MemberRatingProfileRepository.class);
    private final MemberProblemFirstSolveRepository memberProblemFirstSolveRepository =
            mock(MemberProblemFirstSolveRepository.class);

    private final RatingProfileService ratingProfileService =
            new RatingProfileService(memberRatingProfileRepository, memberProblemFirstSolveRepository);

    @Test
    @DisplayName("프로필이 없으면 기본 rating profile을 생성한다")
    void ensureProfile_createDefaultWhenMissing() {
        Member member = Member.of(10L, "new@example.com", "new-user");

        when(memberRatingProfileRepository.findByMemberId(10L)).thenReturn(Optional.empty());
        when(memberRatingProfileRepository.save(any(MemberRatingProfile.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ratingProfileService.ensureProfile(member);

        verify(memberRatingProfileRepository).save(any(MemberRatingProfile.class));
    }

    @Test
    @DisplayName("배틀 결과 반영 시 레이팅/판수/tierScore를 갱신한다")
    void applyBattleResult_updatesProfile() {
        Member member = Member.of(1L, "battle@example.com", "battle-user");
        MemberRatingProfile profile = MemberRatingProfile.createDefault(member);

        when(memberRatingProfileRepository.findByMemberId(1L)).thenReturn(Optional.of(profile));

        ratingProfileService.applyBattleResult(member, 100L);

        assertThat(profile.getBattleRating()).isEqualTo(100);
        assertThat(profile.getBattleMatchCount()).isEqualTo(1);
        assertThat(profile.getTierScore()).isEqualTo(100);
        assertThat(profile.getTier().name()).isEqualTo("BRONZE");
    }

    @Test
    @DisplayName("솔로 첫 AC면 first-solve를 기록하고 솔로 점수/카운트를 올린다")
    void applySoloFirstSolve_recordsAndUpdatesProfile() {
        Member member = Member.of(2L, "solo@example.com", "solo-user");
        Problem problem = mock(Problem.class);
        when(problem.getId()).thenReturn(101L);
        when(problem.getDifficultyRating()).thenReturn(1600);

        MemberRatingProfile profile = MemberRatingProfile.createDefault(member);

        when(memberProblemFirstSolveRepository.existsByMemberIdAndProblemId(2L, 101L))
                .thenReturn(false);
        when(memberProblemFirstSolveRepository.save(any(MemberProblemFirstSolve.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(memberRatingProfileRepository.findByMemberId(2L)).thenReturn(Optional.of(profile));

        boolean applied = ratingProfileService.applySoloFirstSolve(member, problem, LocalDateTime.now());

        assertThat(applied).isTrue();
        assertThat(profile.getFirstSolveScore()).isEqualTo(64);
        assertThat(profile.getFirstSolvedProblemCount()).isEqualTo(1);
        assertThat(profile.getTierScore()).isEqualTo(64);
        assertThat(profile.getTier().name()).isEqualTo("BRONZE");
    }

    @Test
    @DisplayName("배틀 first-AC도 난이도 보너스를 동일하게 1회 반영한다")
    void applyBattleFirstSolve_recordsAndUpdatesProfile() {
        Member member = Member.of(4L, "battle-first@example.com", "battle-first-user");
        Problem problem = mock(Problem.class);
        when(problem.getId()).thenReturn(303L);
        when(problem.getDifficultyRating()).thenReturn(1500);

        MemberRatingProfile profile = MemberRatingProfile.createDefault(member);

        when(memberProblemFirstSolveRepository.existsByMemberIdAndProblemId(4L, 303L))
                .thenReturn(false);
        when(memberProblemFirstSolveRepository.save(any(MemberProblemFirstSolve.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(memberRatingProfileRepository.findByMemberId(4L)).thenReturn(Optional.of(profile));

        boolean applied = ratingProfileService.applyBattleFirstSolve(member, problem, LocalDateTime.now());

        assertThat(applied).isTrue();
        assertThat(profile.getFirstSolveScore()).isEqualTo(60);
        assertThat(profile.getFirstSolvedProblemCount()).isEqualTo(1);
        assertThat(profile.getTierScore()).isEqualTo(60);
    }

    @Test
    @DisplayName("이미 first-solve가 있으면 솔로 보상을 중복 반영하지 않는다")
    void applySoloFirstSolve_skipWhenAlreadyExists() {
        Member member = Member.of(3L, "dup@example.com", "dup-user");
        Problem problem = mock(Problem.class);
        when(problem.getId()).thenReturn(202L);

        when(memberProblemFirstSolveRepository.existsByMemberIdAndProblemId(3L, 202L))
                .thenReturn(true);

        boolean applied = ratingProfileService.applySoloFirstSolve(member, problem, LocalDateTime.now());

        assertThat(applied).isFalse();
        verify(memberProblemFirstSolveRepository, never()).save(any(MemberProblemFirstSolve.class));
        verify(memberRatingProfileRepository, never()).findByMemberId(anyLong());
    }
}
