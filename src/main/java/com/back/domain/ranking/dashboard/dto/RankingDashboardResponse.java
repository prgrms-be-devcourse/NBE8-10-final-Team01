package com.back.domain.ranking.dashboard.dto;

import java.time.LocalDateTime;
import java.util.List;

public record RankingDashboardResponse(
        Profile profile,
        List<ScoreTrendPoint> scoreTrend,
        List<GateProgress> gateProgress,
        List<NearbyRanking> nearbyRanking,
        List<TierDistribution> tierDistribution,
        List<TagStat> tagStats,
        ReviewSummary reviewSummary) {

    public record Profile(
            Long memberId,
            String nickname,
            String tier,
            long rank,
            double percentile,
            long score,
            String nextTier,
            long battleMatchCount,
            int top2Rate,
            long scoreDeltaTotal) {}

    public record ScoreTrendPoint(String label, LocalDateTime occurredAt, long score, long delta) {}

    public record GateProgress(String key, String label, long current, long target, String suffix) {}

    public record NearbyRanking(long rank, Long memberId, String nickname, String tier, long score, boolean isMe) {}

    public record TierDistribution(String tier, long count, double percentage, boolean isMyTier) {}

    public record TagStat(String tag, long solvedCount, long submissionCount, int accuracy) {}

    public record ReviewSummary(long dueTodayCount, long upcomingCount) {}
}
