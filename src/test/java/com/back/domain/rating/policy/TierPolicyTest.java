package com.back.domain.rating.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.back.domain.rating.profile.entity.RatingTier;

class TierPolicyTest {

    @Test
    @DisplayName("overall rating 경계값으로 티어를 계산한다")
    void resolveTierByBoundary() {
        assertThat(TierPolicy.resolveTier(1199)).isEqualTo(RatingTier.BRONZE);
        assertThat(TierPolicy.resolveTier(1200)).isEqualTo(RatingTier.SILVER);
        assertThat(TierPolicy.resolveTier(1400)).isEqualTo(RatingTier.GOLD);
        assertThat(TierPolicy.resolveTier(1600)).isEqualTo(RatingTier.PLATINUM);
        assertThat(TierPolicy.resolveTier(1800)).isEqualTo(RatingTier.DIAMOND);
        assertThat(TierPolicy.resolveTier(2000)).isEqualTo(RatingTier.MASTER);
    }

    @Test
    @DisplayName("활동 전적이 없으면 UNRANKED를 반환한다")
    void resolveDisplayTier_unrankedWhenNoHistory() {
        assertThat(TierPolicy.resolveDisplayTier(RatingTier.BRONZE, 0, 0)).isEqualTo("UNRANKED");
        assertThat(TierPolicy.resolveDisplayTier(null, null, null)).isEqualTo("UNRANKED");
    }

    @Test
    @DisplayName("활동 전적이 있으면 계산된 tier명을 반환한다")
    void resolveDisplayTier_returnsTierNameWhenHasHistory() {
        assertThat(TierPolicy.resolveDisplayTier(RatingTier.SILVER, 1, 0)).isEqualTo("SILVER");
        assertThat(TierPolicy.resolveDisplayTier(RatingTier.GOLD, 0, 3)).isEqualTo("GOLD");
    }

    @Test
    @DisplayName("활동 전적은 있지만 tier가 비어 있으면 UNRANKED를 반환한다")
    void resolveDisplayTier_unrankedWhenTierMissing() {
        assertThat(TierPolicy.resolveDisplayTier(null, 1, 0)).isEqualTo("UNRANKED");
    }
}
