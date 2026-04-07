package com.back.domain.member.member.dto;

import java.util.List;

public record RatingProgressResponse(CurrentProgress current, NextTierProgress next) {

    public record CurrentProgress(
            String displayTier,
            String tier,
            int battleRating,
            int hardBattleRating,
            int activityPoint,
            int battleMatchCount,
            int firstSolvedProblemCount,
            long solved1400Plus,
            long solved1700Plus,
            long solved2000Plus,
            long solved2300Plus,
            double recentTop2Ratio) {}

    public record NextTierProgress(
            String tier,
            boolean eligibleNow,
            String message,
            Integer seatRank,
            List<RequirementProgress> requirements) {}

    public record RequirementProgress(
            String key,
            String label,
            String comparison,
            double current,
            double required,
            double remaining,
            boolean satisfied) {}
}
