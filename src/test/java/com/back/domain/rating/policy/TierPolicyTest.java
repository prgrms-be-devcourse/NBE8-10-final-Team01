package com.back.domain.rating.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.back.domain.rating.profile.entity.RatingTier;

class TierPolicyTest {

    @Test
    @DisplayName("SR은 높아도 AP 게이트를 못 넘으면 티어가 제한된다")
    void resolveTier_cappedByGate() {
        RatingTier tier = TierPolicy.resolveTier(1500, 0, 0, 0, 0, 0, 0.0d);

        assertThat(tier).isEqualTo(RatingTier.BRONZE_1);
    }

    @Test
    @DisplayName("다이아 게이트를 만족하면 SR 컷과 함께 다이아 티어를 계산한다")
    void resolveTier_diamondBySkillAndGate() {
        RatingTier tier = TierPolicy.resolveTier(1500, 1300, 30, 20, 30, 8, 0.6d);

        assertThat(tier).isEqualTo(RatingTier.DIAMOND_1);
    }

    @Test
    @DisplayName("SR/AP/난이도/최근폼을 모두 만족하면 MASTER_1을 계산한다")
    void resolveTier_masterByAllConditions() {
        RatingTier tier = TierPolicy.resolveTier(1800, 2500, 50, 30, 70, 20, 0.7d);

        assertThat(tier).isEqualTo(RatingTier.MASTER_1);
    }

    @Test
    @DisplayName("입력값이 null이어도 안전하게 BRONZE_5를 반환한다")
    void resolveTier_whenNull_returnsBronze5() {
        assertThat(TierPolicy.resolveTier(null, null, 0, 0, 0, 0, 0.0d)).isEqualTo(RatingTier.BRONZE_5);
    }

    @Test
    @DisplayName("활동 전적이 없으면 UNRANKED를 반환한다")
    void resolveDisplayTier_unrankedWhenNoHistory() {
        assertThat(TierPolicy.resolveDisplayTier(RatingTier.BRONZE_5, 0, 0)).isEqualTo("UNRANKED");
        assertThat(TierPolicy.resolveDisplayTier(null, null, null)).isEqualTo("UNRANKED");
    }

    @Test
    @DisplayName("활동 전적이 있으면 계산된 tier명을 반환한다")
    void resolveDisplayTier_returnsTierNameWhenHasHistory() {
        assertThat(TierPolicy.resolveDisplayTier(RatingTier.SILVER_5, 1, 0)).isEqualTo("SILVER_5");
        assertThat(TierPolicy.resolveDisplayTier(RatingTier.GOLD_3, 0, 3)).isEqualTo("GOLD_3");
    }

    @Test
    @DisplayName("활동 전적은 있지만 tier가 비어 있으면 UNRANKED를 반환한다")
    void resolveDisplayTier_unrankedWhenTierMissing() {
        assertThat(TierPolicy.resolveDisplayTier(null, 1, 0)).isEqualTo("UNRANKED");
    }
}
