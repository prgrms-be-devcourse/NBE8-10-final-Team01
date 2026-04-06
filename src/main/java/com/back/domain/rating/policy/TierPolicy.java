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
            Integer hardSkillRating,
            Integer activityPoint,
            long solved1400Plus,
            long solved1700Plus,
            long solved2000Plus,
            long solved2300Plus,
            double recentTop2Ratio) {
        RatingTier srTier = resolveSkillTier(skillRating == null ? 1000 : skillRating);
        RatingTier gateTier = resolveGateTier(
                activityPoint == null ? 0 : activityPoint,
                solved1400Plus,
                solved1700Plus,
                solved2000Plus,
                solved2300Plus,
                recentTop2Ratio);
        RatingTier hardTier = resolveHardTier(hardSkillRating == null ? 1000 : hardSkillRating);

        // 1) SR 컷라인 후보와 2) 활동/난이도 게이트 상한 중 더 낮은 쪽을 적용한다.
        RatingTier resolved = lowerTier(srTier, gateTier);
        if (isAtLeast(resolved, RatingTier.DIAMOND_5)) {
            // 다이아 이상은 Hard SR 컷라인을 추가로 통과해야 한다.
            resolved = lowerTier(resolved, hardTier);
        }

        if (resolved == RatingTier.GOD) {
            // GOD은 전역 TOP 좌석제로만 부여한다.
            return RatingTier.MASTER_1;
        }
        return resolved;
    }

    private static RatingTier resolveSkillTier(int skillRating) {
        if (skillRating >= 2725) return RatingTier.MASTER_1;
        if (skillRating >= 2650) return RatingTier.MASTER_2;
        if (skillRating >= 2575) return RatingTier.MASTER_3;
        if (skillRating >= 2500) return RatingTier.MASTER_4;

        if (skillRating >= 2440) return RatingTier.DIAMOND_1;
        if (skillRating >= 2380) return RatingTier.DIAMOND_2;
        if (skillRating >= 2320) return RatingTier.DIAMOND_3;
        if (skillRating >= 2260) return RatingTier.DIAMOND_4;
        if (skillRating >= 2200) return RatingTier.DIAMOND_5;

        if (skillRating >= 2140) return RatingTier.PLATINUM_1;
        if (skillRating >= 2080) return RatingTier.PLATINUM_2;
        if (skillRating >= 2020) return RatingTier.PLATINUM_3;
        if (skillRating >= 1960) return RatingTier.PLATINUM_4;
        if (skillRating >= 1900) return RatingTier.PLATINUM_5;

        if (skillRating >= 1840) return RatingTier.GOLD_1;
        if (skillRating >= 1780) return RatingTier.GOLD_2;
        if (skillRating >= 1720) return RatingTier.GOLD_3;
        if (skillRating >= 1660) return RatingTier.GOLD_4;
        if (skillRating >= 1600) return RatingTier.GOLD_5;

        if (skillRating >= 1540) return RatingTier.SILVER_1;
        if (skillRating >= 1480) return RatingTier.SILVER_2;
        if (skillRating >= 1420) return RatingTier.SILVER_3;
        if (skillRating >= 1360) return RatingTier.SILVER_4;
        if (skillRating >= 1300) return RatingTier.SILVER_5;

        if (skillRating >= 1240) return RatingTier.BRONZE_1;
        if (skillRating >= 1180) return RatingTier.BRONZE_2;
        if (skillRating >= 1120) return RatingTier.BRONZE_3;
        if (skillRating >= 1060) return RatingTier.BRONZE_4;
        return RatingTier.BRONZE_5;
    }

    private static RatingTier resolveHardTier(int hardSkillRating) {
        if (hardSkillRating >= 2725) return RatingTier.MASTER_1;
        if (hardSkillRating >= 2650) return RatingTier.MASTER_2;
        if (hardSkillRating >= 2575) return RatingTier.MASTER_3;
        if (hardSkillRating >= 2500) return RatingTier.MASTER_4;

        if (hardSkillRating >= 2440) return RatingTier.DIAMOND_1;
        if (hardSkillRating >= 2380) return RatingTier.DIAMOND_2;
        if (hardSkillRating >= 2320) return RatingTier.DIAMOND_3;
        if (hardSkillRating >= 2260) return RatingTier.DIAMOND_4;
        if (hardSkillRating >= 2200) return RatingTier.DIAMOND_5;

        return RatingTier.PLATINUM_1;
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

    private static boolean isAtLeast(RatingTier current, RatingTier standard) {
        return current.ordinal() >= standard.ordinal();
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
