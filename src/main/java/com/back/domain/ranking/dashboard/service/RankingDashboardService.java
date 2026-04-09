package com.back.domain.ranking.dashboard.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.back.domain.ranking.dashboard.dto.RankingDashboardResponse;
import com.back.domain.ranking.dashboard.repository.RankingDashboardQueryRepository;
import com.back.domain.ranking.dashboard.repository.RankingDashboardQueryRepository.BattleSummary;
import com.back.domain.ranking.dashboard.repository.RankingDashboardQueryRepository.BattleTrendRow;
import com.back.domain.ranking.dashboard.repository.RankingDashboardQueryRepository.MemberRankRow;
import com.back.domain.ranking.dashboard.repository.RankingDashboardQueryRepository.TierSourceRow;
import com.back.domain.rating.policy.TierPolicy;
import com.back.domain.rating.profile.entity.RatingTier;
import com.back.global.exception.ServiceException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
// 메인 홈 대시보드 응답을 조립하고 화면용 계산값을 보정한다.
public class RankingDashboardService {

    private static final int TREND_LIMIT = 20;
    private static final int TOP2_WINDOW_SIZE = 20;
    private static final int NEARBY_RANKING_RADIUS = 2;

    // 대시보드에서 쓰는 티어 컷 기준이다.
    private static final List<TierCut> TIER_CUTS = List.of(
            new TierCut("BRONZE_5", 0),
            new TierCut("BRONZE_4", 60),
            new TierCut("BRONZE_3", 120),
            new TierCut("BRONZE_2", 180),
            new TierCut("BRONZE_1", 240),
            new TierCut("SILVER_5", 300),
            new TierCut("SILVER_4", 360),
            new TierCut("SILVER_3", 420),
            new TierCut("SILVER_2", 480),
            new TierCut("SILVER_1", 540),
            new TierCut("GOLD_5", 600),
            new TierCut("GOLD_4", 660),
            new TierCut("GOLD_3", 720),
            new TierCut("GOLD_2", 780),
            new TierCut("GOLD_1", 840),
            new TierCut("PLATINUM_5", 900),
            new TierCut("PLATINUM_4", 960),
            new TierCut("PLATINUM_3", 1020),
            new TierCut("PLATINUM_2", 1080),
            new TierCut("PLATINUM_1", 1140),
            new TierCut("DIAMOND_5", 1200),
            new TierCut("DIAMOND_4", 1260),
            new TierCut("DIAMOND_3", 1320),
            new TierCut("DIAMOND_2", 1380),
            new TierCut("DIAMOND_1", 1440),
            new TierCut("MASTER_4", 1500),
            new TierCut("MASTER_3", 1575),
            new TierCut("MASTER_2", 1650),
            new TierCut("MASTER_1", 1725),
            new TierCut("GOD", Long.MAX_VALUE));

    private final RankingDashboardQueryRepository queryRepository;

    @Transactional(readOnly = true)
    public RankingDashboardResponse getMyDashboard(Long memberId) {
        if (memberId == null || memberId <= 0) {
            throw new ServiceException("MEMBER_400", "Valid member id is required.");
        }

        MemberRankRow member = queryRepository
                .findMemberRank(memberId)
                .orElseThrow(() -> new ServiceException("MEMBER_404", "Member not found."));

        LocalDateTime now = LocalDateTime.now();
        long battleRating = member.battleRating();
        String currentTier =
                displayTier(member.tier(), member.battleMatchCount(), member.firstSolvedProblemCount(), battleRating);
        String nextTier = resolveNextTier(currentTier);
        BattleSummary battleSummary = queryRepository.findBattleSummary(memberId, TOP2_WINDOW_SIZE);

        RankingDashboardResponse.Profile profile = new RankingDashboardResponse.Profile(
                member.memberId(),
                member.nickname(),
                currentTier,
                member.rank(),
                calculatePercentile(member.rank(), member.totalCount()),
                member.score(),
                battleRating,
                nextTier,
                battleSummary.battleMatchCount(),
                battleSummary.top2Rate(),
                battleSummary.top2SampleSize(),
                battleSummary.scoreDeltaTotal());

        return new RankingDashboardResponse(
                profile,
                buildScoreTrend(memberId, battleRating, now),
                buildGateProgress(memberId, battleRating, nextTier, battleSummary.top2Rate()),
                buildNearbyRanking(memberId),
                buildTierDistribution(currentTier),
                queryRepository.findTagStats(memberId),
                queryRepository.findReviewSummary(memberId, now));
    }

    private List<RankingDashboardResponse.ScoreTrendPoint> buildScoreTrend(
            Long memberId, long currentScore, LocalDateTime now) {
        List<BattleTrendRow> recentRows = queryRepository.findRecentBattleTrends(memberId, TREND_LIMIT);
        if (recentRows.isEmpty()) {
            return List.of(new RankingDashboardResponse.ScoreTrendPoint("NOW", now, currentScore, 0));
        }

        List<BattleTrendRow> chronologicalRows = new ArrayList<>(recentRows);
        Collections.reverse(chronologicalRows);

        // 현재 배틀 레이팅에서 최근 delta를 역산해 그래프용 포인트를 재구성한다.
        long recentDeltaSum =
                chronologicalRows.stream().mapToLong(BattleTrendRow::delta).sum();
        long runningScore = currentScore - recentDeltaSum;
        LocalDate today = now.toLocalDate();

        List<RankingDashboardResponse.ScoreTrendPoint> points = new ArrayList<>(chronologicalRows.size());
        for (int i = 0; i < chronologicalRows.size(); i++) {
            BattleTrendRow row = chronologicalRows.get(i);
            runningScore += row.delta();
            LocalDateTime occurredAt = row.occurredAt() == null ? now : row.occurredAt();
            boolean latestPoint = i == chronologicalRows.size() - 1;
            points.add(new RankingDashboardResponse.ScoreTrendPoint(
                    latestPoint ? "NOW" : buildDayLabel(occurredAt, today), occurredAt, runningScore, row.delta()));
        }

        return points;
    }

    private List<RankingDashboardResponse.GateProgress> buildGateProgress(
            Long memberId, long currentScore, String nextTier, int top2Rate) {
        long scoreTarget = nextTier == null || "GOD".equals(nextTier) ? currentScore : resolveTierTarget(nextTier);
        List<RankingDashboardResponse.GateProgress> gates = new ArrayList<>();
        // SCORE 게이트는 화면 라벨에 맞춰 배틀 레이팅 기준으로 내려준다.
        gates.add(new RankingDashboardResponse.GateProgress(
                "SCORE", "\uBC30\uD2C0 \uB808\uC774\uD305", currentScore, scoreTarget, ""));

        if (nextTier == null) {
            return gates;
        }

        Map<Integer, Long> solvedCounts = new HashMap<>();
        if (isTierBetween(nextTier, "GOLD_5", "GOLD_1")) {
            gates.add(buildSolvedGate(memberId, solvedCounts, 1400, 12));
        } else if (isTierBetween(nextTier, "PLATINUM_5", "PLATINUM_1")) {
            gates.add(buildSolvedGate(memberId, solvedCounts, 1700, 20));
            gates.add(buildSolvedGate(memberId, solvedCounts, 2000, 6));
        } else if (isTierBetween(nextTier, "DIAMOND_5", "DIAMOND_1")) {
            gates.add(buildSolvedGate(memberId, solvedCounts, 2000, 30));
            gates.add(buildSolvedGate(memberId, solvedCounts, 2300, 8));
            gates.add(new RankingDashboardResponse.GateProgress("RECENT_TOP2", "Top2", top2Rate, 55, "%"));
        } else if (isTierBetween(nextTier, "MASTER_4", "MASTER_1")) {
            gates.add(buildSolvedGate(memberId, solvedCounts, 2000, 60));
            gates.add(buildSolvedGate(memberId, solvedCounts, 2300, 18));
            gates.add(new RankingDashboardResponse.GateProgress("RECENT_TOP2", "Top2", top2Rate, 65, "%"));
        }

        return gates;
    }

    private RankingDashboardResponse.GateProgress buildSolvedGate(
            Long memberId, Map<Integer, Long> solvedCounts, int difficultyRating, long target) {
        long current = solvedCounts.computeIfAbsent(
                difficultyRating, rating -> queryRepository.countSolvedAtLeast(memberId, rating));
        return new RankingDashboardResponse.GateProgress(
                "SOLVED_" + difficultyRating, difficultyRating + "+", current, target, "");
    }

    private List<RankingDashboardResponse.NearbyRanking> buildNearbyRanking(Long memberId) {
        return queryRepository.findNearbyRanking(memberId, NEARBY_RANKING_RADIUS).stream()
                .map(row -> new RankingDashboardResponse.NearbyRanking(
                        row.rank(),
                        row.memberId(),
                        row.nickname(),
                        displayTier(
                                row.tier(), row.battleMatchCount(), row.firstSolvedProblemCount(), row.battleRating()),
                        row.battleRating(),
                        row.memberId().equals(memberId)))
                .toList();
    }

    private List<RankingDashboardResponse.TierDistribution> buildTierDistribution(String myTier) {
        List<TierSourceRow> rows = queryRepository.findTierSources();
        long totalCount = rows.size();
        Map<String, Long> tierCounts = new LinkedHashMap<>();
        rows.forEach(row -> tierCounts.merge(
                displayTier(row.tier(), row.battleMatchCount(), row.firstSolvedProblemCount(), row.battleRating()),
                1L,
                Long::sum));

        return tierCounts.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> tierOrder(entry.getKey())))
                .map(entry -> new RankingDashboardResponse.TierDistribution(
                        entry.getKey(),
                        entry.getValue(),
                        totalCount == 0 ? 0.0d : round1((double) entry.getValue() * 100.0d / totalCount),
                        entry.getKey().equals(myTier)))
                .toList();
    }

    private String displayTier(String rawTier, int battleMatchCount, int firstSolvedProblemCount, long score) {
        RatingTier parsedTier = null;
        if (rawTier != null && !rawTier.isBlank()) {
            try {
                parsedTier = RatingTier.valueOf(rawTier.trim());
            } catch (IllegalArgumentException ignored) {
                parsedTier = null;
            }
        }
        String resolved = TierPolicy.resolveDisplayTier(parsedTier, battleMatchCount, firstSolvedProblemCount);
        if (!"UNRANKED".equals(resolved)) {
            return resolved;
        }
        if (battleMatchCount == 0 && firstSolvedProblemCount == 0) {
            return "UNRANKED";
        }
        return deriveTierFromScore(score);
    }

    private String deriveTierFromScore(long score) {
        String tier = "BRONZE_5";
        for (TierCut tierCut : TIER_CUTS) {
            if ("GOD".equals(tierCut.tier())) {
                continue;
            }
            if (score >= tierCut.minScore()) {
                tier = tierCut.tier();
            }
        }
        return tier;
    }

    private String resolveNextTier(String currentTier) {
        if ("UNRANKED".equals(currentTier)) {
            return "BRONZE_5";
        }

        int currentOrder = tierOrder(currentTier);
        if (currentOrder < 0 || currentOrder >= TIER_CUTS.size() - 1) {
            return null;
        }
        return TIER_CUTS.get(currentOrder + 1).tier();
    }

    private long resolveTierTarget(String tier) {
        return TIER_CUTS.stream()
                .filter(tierCut -> tierCut.tier().equals(tier))
                .findFirst()
                .map(TierCut::minScore)
                .orElse(0L);
    }

    private boolean isTierBetween(String tier, String startInclusive, String endInclusive) {
        int targetOrder = tierOrder(tier);
        int startOrder = tierOrder(startInclusive);
        int endOrder = tierOrder(endInclusive);
        return targetOrder >= startOrder && targetOrder <= endOrder;
    }

    private int tierOrder(String tier) {
        for (int i = 0; i < TIER_CUTS.size(); i++) {
            if (TIER_CUTS.get(i).tier().equals(tier)) {
                return i;
            }
        }
        return -1;
    }

    private double calculatePercentile(long rank, long totalCount) {
        if (rank <= 0 || totalCount <= 0) {
            return 0.0d;
        }
        return round1((double) rank * 100.0d / totalCount);
    }

    private String buildDayLabel(LocalDateTime occurredAt, LocalDate today) {
        long days = ChronoUnit.DAYS.between(occurredAt.toLocalDate(), today);
        return "D-" + Math.max(days, 0);
    }

    private double round1(double value) {
        return Math.round(value * 10.0d) / 10.0d;
    }

    private record TierCut(String tier, long minScore) {}
}
