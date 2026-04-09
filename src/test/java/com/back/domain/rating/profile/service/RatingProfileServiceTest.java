package com.back.domain.rating.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.domain.member.member.entity.Member;
import com.back.domain.problem.problem.entity.Problem;
import com.back.domain.rating.profile.entity.MemberRatingProfile;
import com.back.domain.rating.profile.entity.RatingTier;
import com.back.domain.rating.profile.repository.MemberRatingProfileRepository;
import com.back.domain.rating.solve.entity.MemberProblemFirstSolve;
import com.back.domain.rating.solve.repository.MemberProblemFirstSolveRepository;

class RatingProfileServiceTest {

    private final MemberRatingProfileRepository memberRatingProfileRepository =
            mock(MemberRatingProfileRepository.class);
    private final MemberProblemFirstSolveRepository memberProblemFirstSolveRepository =
            mock(MemberProblemFirstSolveRepository.class);
    private final BattleParticipantRepository battleParticipantRepository = mock(BattleParticipantRepository.class);

    private final RatingProfileService ratingProfileService = new RatingProfileService(
            memberRatingProfileRepository, memberProblemFirstSolveRepository, battleParticipantRepository);

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
    @DisplayName("배틀 정산 시 Elo 기반 SR과 판수가 갱신된다")
    void applyBattlePlacements_updatesSkillRatingAndMatchCount() {
        Member winner = Member.of(1L, "winner@example.com", "winner");
        Member loser = Member.of(2L, "loser@example.com", "loser");

        MemberRatingProfile winnerProfile = MemberRatingProfile.createDefault(winner);
        MemberRatingProfile loserProfile = MemberRatingProfile.createDefault(loser);
        loserProfile.applyBattleRatingDelta(200);

        when(memberRatingProfileRepository.findByMemberId(1L)).thenReturn(Optional.of(winnerProfile));
        when(memberRatingProfileRepository.findByMemberId(2L)).thenReturn(Optional.of(loserProfile));
        when(battleParticipantRepository.findRecentFinalRanksByMemberId(anyLong(), any(Pageable.class)))
                .thenReturn(List.of(1, 2));

        ratingProfileService.applyBattlePlacements(
                List.of(
                        new RatingProfileService.BattlePlacement(winner, 1),
                        new RatingProfileService.BattlePlacement(loser, 2)),
                2000);

        assertThat(winnerProfile.getBattleMatchCount()).isEqualTo(1);
        assertThat(loserProfile.getBattleMatchCount()).isEqualTo(1);
        assertThat(winnerProfile.getBattleRating()).isGreaterThan(0);
        assertThat(loserProfile.getBattleRating()).isLessThan(200);
    }

    @Test
    @DisplayName("배치 초반 구간 패배는 하락폭을 절반으로 완화한다")
    void applyBattlePlacements_reducesLossDuringPlacementMatches() {
        Member winner = Member.of(11L, "placement-winner@example.com", "placement-winner");
        Member loser = Member.of(12L, "placement-loser@example.com", "placement-loser");

        MemberRatingProfile winnerProfile = MemberRatingProfile.createDefault(winner);
        MemberRatingProfile loserProfile = MemberRatingProfile.createDefault(loser);
        winnerProfile.applyBattleRatingDelta(300);
        loserProfile.applyBattleRatingDelta(300);

        when(memberRatingProfileRepository.findByMemberId(11L)).thenReturn(Optional.of(winnerProfile));
        when(memberRatingProfileRepository.findByMemberId(12L)).thenReturn(Optional.of(loserProfile));
        when(battleParticipantRepository.findRecentFinalRanksByMemberId(anyLong(), any(Pageable.class)))
                .thenReturn(List.of(1, 3, 4));

        ratingProfileService.applyBattlePlacements(
                List.of(
                        new RatingProfileService.BattlePlacement(winner, 1),
                        new RatingProfileService.BattlePlacement(loser, 2)),
                2000);

        // 기본 Elo 하락폭(-32)에 비해 배치 구간 완화(50%)가 적용되어 -16으로 반영된다.
        assertThat(loserProfile.getBattleRating()).isEqualTo(284);
    }

    @Test
    @DisplayName("3등은 최소 +1 SR을 보장한다")
    void applyBattlePlacements_thirdPlaceGetsAtLeastOnePoint() {
        Member first = Member.of(101L, "first@example.com", "first");
        Member second = Member.of(102L, "second@example.com", "second");
        Member third = Member.of(103L, "third@example.com", "third");
        Member fourth = Member.of(104L, "fourth@example.com", "fourth");

        MemberRatingProfile firstProfile = MemberRatingProfile.createDefault(first);
        MemberRatingProfile secondProfile = MemberRatingProfile.createDefault(second);
        MemberRatingProfile thirdProfile = MemberRatingProfile.createDefault(third);
        MemberRatingProfile fourthProfile = MemberRatingProfile.createDefault(fourth);
        firstProfile.applyBattleRatingDelta(300);
        secondProfile.applyBattleRatingDelta(300);
        thirdProfile.applyBattleRatingDelta(300);
        fourthProfile.applyBattleRatingDelta(300);

        when(memberRatingProfileRepository.findByMemberId(101L)).thenReturn(Optional.of(firstProfile));
        when(memberRatingProfileRepository.findByMemberId(102L)).thenReturn(Optional.of(secondProfile));
        when(memberRatingProfileRepository.findByMemberId(103L)).thenReturn(Optional.of(thirdProfile));
        when(memberRatingProfileRepository.findByMemberId(104L)).thenReturn(Optional.of(fourthProfile));
        when(battleParticipantRepository.findRecentFinalRanksByMemberId(anyLong(), any(Pageable.class)))
                .thenReturn(List.of(1, 2, 3, 4));

        var deltas = ratingProfileService.applyBattlePlacements(
                List.of(
                        new RatingProfileService.BattlePlacement(first, 1, false),
                        new RatingProfileService.BattlePlacement(second, 2, false),
                        new RatingProfileService.BattlePlacement(third, 3, false),
                        new RatingProfileService.BattlePlacement(fourth, 4, false)),
                2000);

        assertThat(deltas.get(103L)).isEqualTo(1);
        assertThat(thirdProfile.getBattleRating()).isEqualTo(301);
    }

    @Test
    @DisplayName("포기(QUIT/ABANDONED)는 Elo 결과에 추가 -10 패널티를 적용한다")
    void applyBattlePlacements_forfeitAppliesAdditionalPenalty() {
        Member winner = Member.of(111L, "winner-forfeit@example.com", "winner-forfeit");
        Member quitter = Member.of(112L, "quitter@example.com", "quitter");

        MemberRatingProfile winnerProfile = MemberRatingProfile.createDefault(winner);
        MemberRatingProfile quitterProfile = MemberRatingProfile.createDefault(quitter);
        winnerProfile.applyBattleRatingDelta(300);
        quitterProfile.applyBattleRatingDelta(300);

        when(memberRatingProfileRepository.findByMemberId(111L)).thenReturn(Optional.of(winnerProfile));
        when(memberRatingProfileRepository.findByMemberId(112L)).thenReturn(Optional.of(quitterProfile));
        when(battleParticipantRepository.findRecentFinalRanksByMemberId(anyLong(), any(Pageable.class)))
                .thenReturn(List.of(1, 2));

        var deltas = ratingProfileService.applyBattlePlacements(
                List.of(
                        new RatingProfileService.BattlePlacement(winner, 1, false),
                        new RatingProfileService.BattlePlacement(quitter, 2, true)),
                2000);

        assertThat(deltas.get(112L)).isEqualTo(-26);
        assertThat(quitterProfile.getBattleRating()).isEqualTo(274);
    }

    @Test
    @DisplayName("연패 하락폭 보호는 브론즈/실버에만 적용되고 골드 이상에는 적용되지 않는다")
    void applyBattlePlacements_lossStreakProtection_onlyForBronzeAndSilver() {
        Member silver1 = Member.of(21L, "silver-1@example.com", "silver-1");
        Member silver2 = Member.of(22L, "silver-2@example.com", "silver-2");
        Member silver3 = Member.of(23L, "silver-3@example.com", "silver-3");
        Member silverLoser = Member.of(24L, "silver-loser@example.com", "silver-loser");
        Member gold1 = Member.of(31L, "gold-1@example.com", "gold-1");
        Member gold2 = Member.of(32L, "gold-2@example.com", "gold-2");
        Member gold3 = Member.of(33L, "gold-3@example.com", "gold-3");
        Member goldLoser = Member.of(34L, "gold-loser@example.com", "gold-loser");

        MemberRatingProfile silver1Profile = MemberRatingProfile.createDefault(silver1);
        MemberRatingProfile silver2Profile = MemberRatingProfile.createDefault(silver2);
        MemberRatingProfile silver3Profile = MemberRatingProfile.createDefault(silver3);
        MemberRatingProfile silverLoserProfile = MemberRatingProfile.createDefault(silverLoser);
        MemberRatingProfile gold1Profile = MemberRatingProfile.createDefault(gold1);
        MemberRatingProfile gold2Profile = MemberRatingProfile.createDefault(gold2);
        MemberRatingProfile gold3Profile = MemberRatingProfile.createDefault(gold3);
        MemberRatingProfile goldLoserProfile = MemberRatingProfile.createDefault(goldLoser);
        silver1Profile.applyBattleRatingDelta(300);
        silver2Profile.applyBattleRatingDelta(300);
        silver3Profile.applyBattleRatingDelta(300);
        silverLoserProfile.applyBattleRatingDelta(300);
        gold1Profile.applyBattleRatingDelta(300);
        gold2Profile.applyBattleRatingDelta(300);
        gold3Profile.applyBattleRatingDelta(300);
        goldLoserProfile.applyBattleRatingDelta(300);
        goldLoserProfile.updateTier(RatingTier.GOLD_5);

        when(memberRatingProfileRepository.findByMemberId(21L)).thenReturn(Optional.of(silver1Profile));
        when(memberRatingProfileRepository.findByMemberId(22L)).thenReturn(Optional.of(silver2Profile));
        when(memberRatingProfileRepository.findByMemberId(23L)).thenReturn(Optional.of(silver3Profile));
        when(memberRatingProfileRepository.findByMemberId(24L)).thenReturn(Optional.of(silverLoserProfile));
        when(memberRatingProfileRepository.findByMemberId(31L)).thenReturn(Optional.of(gold1Profile));
        when(memberRatingProfileRepository.findByMemberId(32L)).thenReturn(Optional.of(gold2Profile));
        when(memberRatingProfileRepository.findByMemberId(33L)).thenReturn(Optional.of(gold3Profile));
        when(memberRatingProfileRepository.findByMemberId(34L)).thenReturn(Optional.of(goldLoserProfile));
        when(battleParticipantRepository.findRecentFinalRanksByMemberId(anyLong(), any(Pageable.class)))
                .thenReturn(List.of(4, 4, 3));

        ratingProfileService.applyBattlePlacements(
                List.of(
                        new RatingProfileService.BattlePlacement(silver1, 1),
                        new RatingProfileService.BattlePlacement(silver2, 2),
                        new RatingProfileService.BattlePlacement(silver3, 3),
                        new RatingProfileService.BattlePlacement(silverLoser, 4)),
                2000);
        ratingProfileService.applyBattlePlacements(
                List.of(
                        new RatingProfileService.BattlePlacement(gold1, 1),
                        new RatingProfileService.BattlePlacement(gold2, 2),
                        new RatingProfileService.BattlePlacement(gold3, 3),
                        new RatingProfileService.BattlePlacement(goldLoser, 4)),
                2000);

        // 브론즈/실버는 3연패 이상에서 하락폭이 -12 바닥으로 보호된다.
        assertThat(silverLoserProfile.getBattleRating()).isEqualTo(288);
        // 골드 이상은 같은 조건에서도 보호 로직이 적용되지 않는다.
        assertThat(goldLoserProfile.getBattleRating()).isEqualTo(284);
    }

    @Test
    @DisplayName("SR은 최소 0 바닥을 유지해 추가 하락을 막는다")
    void applyBattlePlacements_appliesSkillRatingFloor() {
        Member winner = Member.of(41L, "floor-winner@example.com", "floor-winner");
        Member floorMember = Member.of(42L, "floor-member@example.com", "floor-member");

        MemberRatingProfile winnerProfile = MemberRatingProfile.createDefault(winner);
        MemberRatingProfile floorProfile = MemberRatingProfile.createDefault(floorMember);
        floorProfile.applyBattleRatingDelta(5);
        floorProfile.updateTier(RatingTier.GOLD_5);
        for (int i = 0; i < 30; i++) {
            floorProfile.increaseBattleMatchCount();
        }

        when(memberRatingProfileRepository.findByMemberId(41L)).thenReturn(Optional.of(winnerProfile));
        when(memberRatingProfileRepository.findByMemberId(42L)).thenReturn(Optional.of(floorProfile));
        when(battleParticipantRepository.findRecentFinalRanksByMemberId(anyLong(), any(Pageable.class)))
                .thenReturn(List.of(4, 4, 4));

        ratingProfileService.applyBattlePlacements(
                List.of(
                        new RatingProfileService.BattlePlacement(winner, 1),
                        new RatingProfileService.BattlePlacement(floorMember, 2)),
                2200);

        assertThat(floorProfile.getBattleRating()).isEqualTo(0);
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
        when(battleParticipantRepository.findRecentFinalRanksByMemberId(anyLong(), any(Pageable.class)))
                .thenReturn(List.of());

        boolean applied = ratingProfileService.applySoloFirstSolve(member, problem, LocalDateTime.now());

        assertThat(applied).isTrue();
        assertThat(profile.getFirstSolveScore()).isEqualTo(15);
        assertThat(profile.getFirstSolvedProblemCount()).isEqualTo(1);
        assertThat(profile.getTierScore()).isEqualTo(0);
        assertThat(profile.getTier().name()).isEqualTo("BRONZE_5");
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
        when(battleParticipantRepository.findRecentFinalRanksByMemberId(anyLong(), any(Pageable.class)))
                .thenReturn(List.of());

        boolean applied = ratingProfileService.applyBattleFirstSolve(member, problem, LocalDateTime.now());

        assertThat(applied).isTrue();
        assertThat(profile.getFirstSolveScore()).isEqualTo(14);
        assertThat(profile.getFirstSolvedProblemCount()).isEqualTo(1);
        assertThat(profile.getTierScore()).isEqualTo(0);
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
