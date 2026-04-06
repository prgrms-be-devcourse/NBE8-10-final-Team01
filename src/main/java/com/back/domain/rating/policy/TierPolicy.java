package com.back.domain.rating.policy;

import com.back.domain.rating.profile.entity.RatingTier;

public final class TierPolicy {
    private static final String UNRANKED = "UNRANKED";

    private TierPolicy() {}

    // 현재 운영 기준 컷라인. 밸런싱 시 이 메서드만 조정하면 된다.
    public static RatingTier resolveTier(int tierScore) {
        if (tierScore >= 2000) return RatingTier.MASTER;
        if (tierScore >= 1800) return RatingTier.DIAMOND;
        if (tierScore >= 1600) return RatingTier.PLATINUM;
        if (tierScore >= 1400) return RatingTier.GOLD;
        if (tierScore >= 1200) return RatingTier.SILVER;
        return RatingTier.BRONZE;
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
