package com.back.domain.member.member.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import com.back.domain.battle.battleparticipant.repository.BattleParticipantRepository;
import com.back.domain.member.member.dto.RatingProgressResponse;
import com.back.domain.member.member.entity.Member;
import com.back.domain.member.member.repository.MemberRepository;
import com.back.domain.rating.policy.TierPolicy;
import com.back.domain.rating.profile.entity.MemberRatingProfile;
import com.back.domain.rating.profile.entity.RatingTier;
import com.back.domain.rating.profile.repository.MemberRatingProfileRepository;
import com.back.domain.rating.solve.repository.MemberProblemFirstSolveRepository;
import com.back.global.exception.ServiceException;
import com.back.global.rsData.RsData;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MemberRatingProgressService {

    private static final int INITIAL_SKILL_RATING = 0;
    private static final int INITIAL_ACTIVITY_POINT = 0;
    private static final int TOP2_WINDOW_SIZE = 20;
    private static final String COMPARISON_AT_LEAST = "AT_LEAST";
    private static final String COMPARISON_AT_MOST = "AT_MOST";
    private static final String UNRANKED = "UNRANKED";

    private final MemberRepository memberRepository;
    private final MemberRatingProfileRepository memberRatingProfileRepository;
    private final MemberProblemFirstSolveRepository memberProblemFirstSolveRepository;
    private final BattleParticipantRepository battleParticipantRepository;

    public RsData<RatingProgressResponse> getMyRatingProgress(Long memberId) {
        if (memberId == null || memberId <= 0) {
            throw new ServiceException("MEMBER_400", "유효한 회원 ID가 필요합니다");
        }

        Member member = memberRepository
                .findById(memberId)
                .orElseThrow(() -> new ServiceException("MEMBER_404", "존재하지 않는 회원입니다"));

        MemberRatingProfile profile = memberRatingProfileRepository
                .findByMemberId(memberId)
                .orElseGet(() -> MemberRatingProfile.createDefault(member));

        int battleMatchCount = nullToZero(profile.getBattleMatchCount());
        int firstSolvedProblemCount = nullToZero(profile.getFirstSolvedProblemCount());
        int battleRating = nullToDefault(profile.getBattleRating(), INITIAL_SKILL_RATING);
        int activityPoint = nullToDefault(profile.getFirstSolveScore(), INITIAL_ACTIVITY_POINT);
        RatingTier currentTier = profile.getTier() == null ? RatingTier.BRONZE_5 : profile.getTier();
        String displayTier = TierPolicy.resolveDisplayTier(currentTier, battleMatchCount, firstSolvedProblemCount);

        long solved1400Plus =
                memberProblemFirstSolveRepository.countByMemberIdAndProblemDifficultyRatingGreaterThanEqual(
                        memberId, 1400);
        long solved1700Plus =
                memberProblemFirstSolveRepository.countByMemberIdAndProblemDifficultyRatingGreaterThanEqual(
                        memberId, 1700);
        long solved2000Plus =
                memberProblemFirstSolveRepository.countByMemberIdAndProblemDifficultyRatingGreaterThanEqual(
                        memberId, 2000);
        long solved2300Plus =
                memberProblemFirstSolveRepository.countByMemberIdAndProblemDifficultyRatingGreaterThanEqual(
                        memberId, 2300);
        double recentTop2Ratio = resolveRecentTop2Ratio(memberId);

        RatingProgressResponse.CurrentProgress currentProgress = new RatingProgressResponse.CurrentProgress(
                displayTier,
                currentTier.name(),
                battleRating,
                activityPoint,
                battleMatchCount,
                firstSolvedProblemCount,
                solved1400Plus,
                solved1700Plus,
                solved2000Plus,
                solved2300Plus,
                recentTop2Ratio);

        RatingProgressResponse.NextTierProgress nextProgress = resolveNextTierProgress(
                memberId,
                displayTier,
                currentTier,
                battleRating,
                activityPoint,
                battleMatchCount,
                firstSolvedProblemCount,
                solved1400Plus,
                solved1700Plus,
                solved2000Plus,
                solved2300Plus,
                recentTop2Ratio);

        return RsData.of("200", "내 레이팅 진행도 조회 성공", new RatingProgressResponse(currentProgress, nextProgress));
    }

    private RatingProgressResponse.NextTierProgress resolveNextTierProgress(
            Long memberId,
            String displayTier,
            RatingTier currentTier,
            int battleRating,
            int activityPoint,
            int battleMatchCount,
            int firstSolvedProblemCount,
            long solved1400Plus,
            long solved1700Plus,
            long solved2000Plus,
            long solved2300Plus,
            double recentTop2Ratio) {
        RatingTier nextTier = resolveNextTier(displayTier, currentTier);
        if (nextTier == null) {
            return null;
        }

        if (UNRANKED.equals(displayTier)) {
            boolean hasActivity = battleMatchCount > 0 || firstSolvedProblemCount > 0;
            RatingProgressResponse.RequirementProgress activityRequirement =
                    new RatingProgressResponse.RequirementProgress(
                            "rankedActivity",
                            "랭크 활동(배틀 1판 또는 first solve 1회)",
                            COMPARISON_AT_LEAST,
                            hasActivity ? 1.0d : 0.0d,
                            1.0d,
                            hasActivity ? 0.0d : 1.0d,
                            hasActivity);

            return new RatingProgressResponse.NextTierProgress(
                    nextTier.name(),
                    hasActivity,
                    hasActivity ? "활동이 반영되어 다음 조회부터 BRONZE_5로 표시됩니다." : "배틀 1판 또는 first solve 1회를 완료하세요.",
                    null,
                    List.of(activityRequirement));
        }

        if (nextTier == RatingTier.GOD) {
            Integer seatRank = resolveMasterSeatRank(memberId);
            double currentRank = seatRank == null ? 9999.0d : seatRank.doubleValue();
            boolean seatSatisfied = seatRank != null && seatRank <= 5;
            RatingProgressResponse.RequirementProgress seatRequirement = new RatingProgressResponse.RequirementProgress(
                    "godSeatRank",
                    "MASTER_1 상위 5석(SR 순위)",
                    COMPARISON_AT_MOST,
                    currentRank,
                    5.0d,
                    seatRank == null ? 9994.0d : Math.max(0.0d, seatRank - 5.0d),
                    seatSatisfied);

            return new RatingProgressResponse.NextTierProgress(
                    nextTier.name(),
                    seatSatisfied,
                    "GOD은 MASTER_1 유저 중 SR 상위 5명에게만 부여됩니다.",
                    seatRank,
                    List.of(seatRequirement));
        }

        List<RatingProgressResponse.RequirementProgress> requirements = new ArrayList<>();

        Integer requiredSkillRating = resolveSkillThreshold(nextTier);
        if (requiredSkillRating != null) {
            requirements.add(
                    buildAtLeastRequirement("battleRating", "SR (battleRating)", battleRating, requiredSkillRating));
        }

        GateRequirement gateRequirement = resolveGateRequirement(nextTier);
        if (gateRequirement.activityPoint != null) {
            requirements.add(buildAtLeastRequirement(
                    "activityPoint", "AP (firstSolveScore)", activityPoint, gateRequirement.activityPoint));
        }
        if (gateRequirement.solved1400Plus != null) {
            requirements.add(buildAtLeastRequirement(
                    "solved1400Plus", "1400+ first solve", solved1400Plus, gateRequirement.solved1400Plus));
        }
        if (gateRequirement.solved1700Plus != null) {
            requirements.add(buildAtLeastRequirement(
                    "solved1700Plus", "1700+ first solve", solved1700Plus, gateRequirement.solved1700Plus));
        }
        if (gateRequirement.solved2000Plus != null) {
            requirements.add(buildAtLeastRequirement(
                    "solved2000Plus", "2000+ first solve", solved2000Plus, gateRequirement.solved2000Plus));
        }
        if (gateRequirement.solved2300Plus != null) {
            requirements.add(buildAtLeastRequirement(
                    "solved2300Plus", "2300+ first solve", solved2300Plus, gateRequirement.solved2300Plus));
        }
        if (gateRequirement.recentTop2Ratio != null) {
            requirements.add(buildAtLeastRequirement(
                    "recentTop2Ratio", "최근 20판 Top2 비율", recentTop2Ratio, gateRequirement.recentTop2Ratio));
        }

        boolean eligibleNow = requirements.stream().allMatch(RatingProgressResponse.RequirementProgress::satisfied);
        String message = eligibleNow ? "다음 정산 시 승급 가능한 상태입니다." : "남은 조건을 채우면 다음 티어로 승급할 수 있습니다.";

        return new RatingProgressResponse.NextTierProgress(nextTier.name(), eligibleNow, message, null, requirements);
    }

    private RatingTier resolveNextTier(String displayTier, RatingTier currentTier) {
        if (UNRANKED.equals(displayTier)) {
            return RatingTier.BRONZE_5;
        }

        if (currentTier == null || currentTier == RatingTier.GOD) {
            return null;
        }

        RatingTier[] tiers = RatingTier.values();
        int nextOrdinal = currentTier.ordinal() + 1;
        if (nextOrdinal >= tiers.length) {
            return null;
        }
        return tiers[nextOrdinal];
    }

    private Integer resolveSkillThreshold(RatingTier tier) {
        return switch (tier) {
            case BRONZE_5 -> 0;
            case BRONZE_4 -> 60;
            case BRONZE_3 -> 120;
            case BRONZE_2 -> 180;
            case BRONZE_1 -> 240;
            case SILVER_5 -> 300;
            case SILVER_4 -> 360;
            case SILVER_3 -> 420;
            case SILVER_2 -> 480;
            case SILVER_1 -> 540;
            case GOLD_5 -> 600;
            case GOLD_4 -> 660;
            case GOLD_3 -> 720;
            case GOLD_2 -> 780;
            case GOLD_1 -> 840;
            case PLATINUM_5 -> 900;
            case PLATINUM_4 -> 960;
            case PLATINUM_3 -> 1020;
            case PLATINUM_2 -> 1080;
            case PLATINUM_1 -> 1140;
            case DIAMOND_5 -> 1200;
            case DIAMOND_4 -> 1260;
            case DIAMOND_3 -> 1320;
            case DIAMOND_2 -> 1380;
            case DIAMOND_1 -> 1440;
            case MASTER_4 -> 1500;
            case MASTER_3 -> 1575;
            case MASTER_2 -> 1650;
            case MASTER_1 -> 1725;
            case GOD -> null;
        };
    }

    private GateRequirement resolveGateRequirement(RatingTier tier) {
        if (tier.ordinal() <= RatingTier.BRONZE_1.ordinal()) {
            return GateRequirement.none();
        }
        if (tier.ordinal() <= RatingTier.SILVER_1.ordinal()) {
            return new GateRequirement(60, null, null, null, null, null);
        }
        if (tier.ordinal() <= RatingTier.GOLD_1.ordinal()) {
            return new GateRequirement(220, 12L, null, null, null, null);
        }
        if (tier.ordinal() <= RatingTier.PLATINUM_1.ordinal()) {
            return new GateRequirement(600, null, 20L, 6L, null, null);
        }
        if (tier.ordinal() <= RatingTier.DIAMOND_1.ordinal()) {
            return new GateRequirement(1300, null, null, 30L, 8L, 0.55d);
        }
        if (tier.ordinal() <= RatingTier.MASTER_1.ordinal()) {
            return new GateRequirement(2400, null, null, 60L, 18L, 0.65d);
        }
        return GateRequirement.none();
    }

    private Integer resolveMasterSeatRank(Long memberId) {
        List<MemberRatingProfile> candidates =
                memberRatingProfileRepository.findAllByTierInOrderByBattleRatingDescFirstSolveScoreDescMemberIdAsc(
                        List.of(RatingTier.GOD, RatingTier.MASTER_1));
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }

        for (int i = 0; i < candidates.size(); i++) {
            MemberRatingProfile profile = candidates.get(i);
            if (profile == null || profile.getMember() == null) continue;
            if (Objects.equals(profile.getMember().getId(), memberId)) {
                return i + 1;
            }
        }
        return null;
    }

    private double resolveRecentTop2Ratio(Long memberId) {
        List<Integer> recentRanks = battleParticipantRepository.findRecentFinalRanksByMemberId(
                memberId, PageRequest.of(0, TOP2_WINDOW_SIZE));
        if (recentRanks == null || recentRanks.isEmpty()) {
            return 0.0d;
        }

        long top2Count =
                recentRanks.stream().filter(rank -> rank != null && rank <= 2).count();
        return round((double) top2Count / recentRanks.size());
    }

    private RatingProgressResponse.RequirementProgress buildAtLeastRequirement(
            String key, String label, double current, double required) {
        boolean satisfied = current >= required;
        double remaining = satisfied ? 0.0d : round(required - current);
        return new RatingProgressResponse.RequirementProgress(
                key, label, COMPARISON_AT_LEAST, round(current), round(required), remaining, satisfied);
    }

    private int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }

    private int nullToDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private double round(double value) {
        return Math.round(value * 10000.0d) / 10000.0d;
    }

    private record GateRequirement(
            Integer activityPoint,
            Long solved1400Plus,
            Long solved1700Plus,
            Long solved2000Plus,
            Long solved2300Plus,
            Double recentTop2Ratio) {
        private static GateRequirement none() {
            return new GateRequirement(null, null, null, null, null, null);
        }
    }
}
