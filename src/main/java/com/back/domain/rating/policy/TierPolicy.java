package com.back.domain.rating.policy;

import com.back.domain.rating.profile.entity.RatingTier;

public final class TierPolicy {
    private static final String UNRANKED = "UNRANKED";
    private static final double DIAMOND_TOP2_RATIO = 0.55d;
    private static final double MASTER_TOP2_RATIO = 0.65d;

    private TierPolicy() {}

    // 티어는 SR 기반 후보와 AP/난이도 게이트 상한을 동시에 만족해야 한다.
    public static RatingTier resolveTier(
            Integer skillRating,
            Integer activityPoint,
            long solved1400Plus,
            long solved1700Plus,
            long solved2000Plus,
            long solved2300Plus,
            double recentTop2Ratio) {
        RatingTier srTier = resolveSkillTier(skillRating == null ? 0 : skillRating);
        RatingTier gateTier = resolveGateTier(
                activityPoint == null ? 0 : activityPoint,
                solved1400Plus,
                solved1700Plus,
                solved2000Plus,
                solved2300Plus,
                recentTop2Ratio);

        // 1) SR 컷라인 후보와 2) 활동/난이도 게이트 상한 중 더 낮은 쪽을 적용한다.
        RatingTier resolved = lowerTier(srTier, gateTier);

        if (resolved == RatingTier.GOD) {
            // GOD은 전역 TOP 좌석제로만 부여한다.
            return RatingTier.MASTER_1;
        }
        return resolved;
    }

    private static RatingTier resolveSkillTier(int skillRating) {
        if (skillRating >= 1725) return RatingTier.MASTER_1;
        if (skillRating >= 1650) return RatingTier.MASTER_2;
        if (skillRating >= 1575) return RatingTier.MASTER_3;
        if (skillRating >= 1500) return RatingTier.MASTER_4;

        if (skillRating >= 1440) return RatingTier.DIAMOND_1;
        if (skillRating >= 1380) return RatingTier.DIAMOND_2;
        if (skillRating >= 1320) return RatingTier.DIAMOND_3;
        if (skillRating >= 1260) return RatingTier.DIAMOND_4;
        if (skillRating >= 1200) return RatingTier.DIAMOND_5;

        if (skillRating >= 1140) return RatingTier.PLATINUM_1;
        if (skillRating >= 1080) return RatingTier.PLATINUM_2;
        if (skillRating >= 1020) return RatingTier.PLATINUM_3;
        if (skillRating >= 960) return RatingTier.PLATINUM_4;
        if (skillRating >= 900) return RatingTier.PLATINUM_5;

        if (skillRating >= 840) return RatingTier.GOLD_1;
        if (skillRating >= 780) return RatingTier.GOLD_2;
        if (skillRating >= 720) return RatingTier.GOLD_3;
        if (skillRating >= 660) return RatingTier.GOLD_4;
        if (skillRating >= 600) return RatingTier.GOLD_5;

        if (skillRating >= 540) return RatingTier.SILVER_1;
        if (skillRating >= 480) return RatingTier.SILVER_2;
        if (skillRating >= 420) return RatingTier.SILVER_3;
        if (skillRating >= 360) return RatingTier.SILVER_4;
        if (skillRating >= 300) return RatingTier.SILVER_5;

        if (skillRating >= 240) return RatingTier.BRONZE_1;
        if (skillRating >= 180) return RatingTier.BRONZE_2;
        if (skillRating >= 120) return RatingTier.BRONZE_3;
        if (skillRating >= 60) return RatingTier.BRONZE_4;
        return RatingTier.BRONZE_5;
    }

    private static RatingTier resolveGateTier(
            int activityPoint,
            long solved1400Plus,
            long solved1700Plus,
            long solved2000Plus,
            long solved2300Plus,
            double recentTop2Ratio) {
        // 상위 게이트부터 차례로 평가해 가장 높은 "허용 상한 티어"를 반환한다.
        // 최소 판수는 의도적으로 제거하고, 최근 Top2 폼으로 경쟁력을 보정한다.
        if (activityPoint >= 2400
                && solved2000Plus >= 60
                && solved2300Plus >= 18
                && recentTop2Ratio >= MASTER_TOP2_RATIO) {
            return RatingTier.MASTER_1;
        }
        if (activityPoint >= 1300
                && solved2000Plus >= 30
                && solved2300Plus >= 8
                && recentTop2Ratio >= DIAMOND_TOP2_RATIO) {
            return RatingTier.DIAMOND_1;
        }
        if (activityPoint >= 600 && solved1700Plus >= 20 && solved2000Plus >= 6) {
            return RatingTier.PLATINUM_1;
        }
        if (activityPoint >= 220 && solved1400Plus >= 12) {
            return RatingTier.GOLD_1;
        }
        if (activityPoint >= 60) {
            return RatingTier.SILVER_1;
        }
        return RatingTier.BRONZE_1;
    }

    private static RatingTier lowerTier(RatingTier first, RatingTier second) {
        return first.ordinal() <= second.ordinal() ? first : second;
    }

    // 활동 전적이 없으면 UNRANKED, 그 외에는 계산된 티어명을 노출한다.
    public static String resolveDisplayTier(
            RatingTier tier, Integer battleMatchCount, Integer firstSolvedProblemCount) {
        int battleCount = battleMatchCount == null ? 0 : battleMatchCount;
        int solvedCount = firstSolvedProblemCount == null ? 0 : firstSolvedProblemCount;

        if (battleCount == 0 && solvedCount == 0) {
            return UNRANKED;
        }

        return tier != null ? tier.name() : UNRANKED;
    }
}
