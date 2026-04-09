package com.back.domain.ranking.dashboard.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import com.back.domain.ranking.dashboard.dto.RankingDashboardResponse;

import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
// 메인 홈 대시보드에 필요한 랭킹/배틀 집계 전용 조회를 모아둔다.
public class RankingDashboardQueryRepository {
    private static final long DEFAULT_BATTLE_RATING = 0L;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Optional<MemberRankRow> findMemberRank(Long memberId) {
        String sql = """
                with ranked as (
                    select
                        m.id as member_id,
                        m.nickname,
                        mrp.tier,
                        coalesce(mrp.battle_rating, :defaultBattleRating) as score,
                        coalesce(mrp.battle_rating, :defaultBattleRating) as battle_rating,
                        coalesce(mrp.battle_match_count, 0) as battle_match_count,
                        coalesce(mrp.first_solved_problem_count, 0) as first_solved_problem_count,
                        row_number() over (
                            order by coalesce(mrp.battle_rating, :defaultBattleRating) desc, m.id asc
                        ) as rank_no,
                        count(*) over () as total_count
                    from members m
                    left join member_rating_profiles mrp on mrp.member_id = m.id
                )
                select member_id, nickname, tier, score, battle_rating, battle_match_count, first_solved_problem_count, rank_no, total_count
                from ranked
                where member_id = :memberId
                """;

        return queryForOptional(
                sql,
                Map.of("memberId", memberId, "defaultBattleRating", DEFAULT_BATTLE_RATING),
                (rs, rowNum) -> new MemberRankRow(
                        rs.getLong("member_id"),
                        rs.getString("nickname"),
                        rs.getString("tier"),
                        getLong(rs, "score"),
                        getLong(rs, "battle_rating"),
                        getInt(rs, "battle_match_count"),
                        getInt(rs, "first_solved_problem_count"),
                        getLong(rs, "rank_no"),
                        getLong(rs, "total_count")));
    }

    public BattleSummary findBattleSummary(Long memberId, int top2WindowSize) {
        // 전체 완료 배틀 수는 누적 기준으로 유지한다.
        String summarySql = """
                select
                    count(*) as battle_match_count
                from battle_participants bp
                join battle_rooms br on br.id = bp.room_id
                where bp.user_id = :memberId
                  and upper(br.status) = 'FINISHED'
                """;

        Long battleMatchCount = jdbcTemplate.queryForObject(
                summarySql, Map.of("memberId", memberId), (rs, rowNum) -> getLong(rs, "battle_match_count"));

        // Top2 비율과 최근 변동 합계는 최근 완료 배틀 최대 20판 기준으로 계산한다.
        String recentBattleWindowSql = """
                select
                    bp.final_rank,
                    coalesce(bp.score_delta, 0) as score_delta
                from battle_participants bp
                join battle_rooms br on br.id = bp.room_id
                where bp.user_id = :memberId
                  and upper(br.status) = 'FINISHED'
                  and bp.final_rank is not null
                order by coalesce(bp.finish_time, br.timer_end, br.modified_at, br.created_at, bp.created_at) desc,
                         bp.id desc
                limit :limit
                """;

        SqlParameterSource params =
                new MapSqlParameterSource().addValue("memberId", memberId).addValue("limit", top2WindowSize);
        List<RecentBattleWindowRow> recentBattleWindow = jdbcTemplate.query(
                recentBattleWindowSql,
                params,
                (rs, rowNum) -> new RecentBattleWindowRow(getInt(rs, "final_rank"), getLong(rs, "score_delta")));
        int top2Rate = calculateTop2Rate(recentBattleWindow);
        int top2SampleSize = recentBattleWindow.size();
        long recentScoreDeltaTotal = recentBattleWindow.stream()
                .mapToLong(RecentBattleWindowRow::scoreDelta)
                .sum();

        return new BattleSummary(
                battleMatchCount == null ? 0L : battleMatchCount, top2Rate, top2SampleSize, recentScoreDeltaTotal);
    }

    public List<BattleTrendRow> findRecentBattleTrends(Long memberId, int limit) {
        String sql = """
                select
                    coalesce(bp.finish_time, br.timer_end, br.modified_at, br.created_at, bp.created_at) as occurred_at,
                    coalesce(bp.score_delta, 0) as delta
                from battle_participants bp
                join battle_rooms br on br.id = bp.room_id
                where bp.user_id = :memberId
                  and upper(br.status) = 'FINISHED'
                order by coalesce(bp.finish_time, br.timer_end, br.modified_at, br.created_at, bp.created_at) desc,
                         bp.id desc
                limit :limit
                """;

        SqlParameterSource params =
                new MapSqlParameterSource().addValue("memberId", memberId).addValue("limit", limit);
        return jdbcTemplate.query(
                sql,
                params,
                (rs, rowNum) -> new BattleTrendRow(getLocalDateTime(rs, "occurred_at"), getLong(rs, "delta")));
    }

    public long countSolvedAtLeast(Long memberId, int difficultyRating) {
        String sql = """
                select count(distinct coalesce(s.problem_id, br.problem_id)) as solved_count
                from submissions s
                left join battle_rooms br on br.id = s.room_id
                join problems p on p.id = coalesce(s.problem_id, br.problem_id)
                where s.user_id = :memberId
                  and s.result = 'AC'
                  and p.difficulty_rating >= :difficultyRating
                """;

        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("memberId", memberId)
                .addValue("difficultyRating", difficultyRating);
        Long count = jdbcTemplate.queryForObject(sql, params, Long.class);
        return count == null ? 0L : count;
    }

    public List<NearbyRankingRow> findNearbyRanking(Long memberId, int radius) {
        String sql = """
                with ranked as (
                    select
                        m.id as member_id,
                        m.nickname,
                        mrp.tier,
                        coalesce(mrp.battle_rating, :defaultBattleRating) as battle_rating,
                        coalesce(mrp.battle_match_count, 0) as battle_match_count,
                        coalesce(mrp.first_solved_problem_count, 0) as first_solved_problem_count,
                        row_number() over (
                            order by coalesce(mrp.battle_rating, :defaultBattleRating) desc, m.id asc
                        ) as rank_no
                    from members m
                    left join member_rating_profiles mrp on mrp.member_id = m.id
                ),
                me as (
                    select rank_no
                    from ranked
                    where member_id = :memberId
                )
                select r.rank_no, r.member_id, r.nickname, r.tier, r.battle_rating, r.battle_match_count, r.first_solved_problem_count
                from ranked r
                cross join me
                where r.rank_no between me.rank_no - :radius and me.rank_no + :radius
                order by r.rank_no asc
                """;

        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("memberId", memberId)
                .addValue("radius", radius)
                .addValue("defaultBattleRating", DEFAULT_BATTLE_RATING);
        return jdbcTemplate.query(
                sql,
                params,
                (rs, rowNum) -> new NearbyRankingRow(
                        getLong(rs, "rank_no"),
                        rs.getLong("member_id"),
                        rs.getString("nickname"),
                        rs.getString("tier"),
                        getLong(rs, "battle_rating"),
                        getInt(rs, "battle_match_count"),
                        getInt(rs, "first_solved_problem_count")));
    }

    public List<TierSourceRow> findTierSources() {
        String sql = """
                select
                    mrp.tier,
                    coalesce(mrp.battle_rating, :defaultBattleRating) as battle_rating,
                    coalesce(mrp.battle_match_count, 0) as battle_match_count,
                    coalesce(mrp.first_solved_problem_count, 0) as first_solved_problem_count
                from members m
                left join member_rating_profiles mrp on mrp.member_id = m.id
                """;
        return jdbcTemplate.query(
                sql,
                Map.of("defaultBattleRating", DEFAULT_BATTLE_RATING),
                (rs, rowNum) -> new TierSourceRow(
                        rs.getString("tier"),
                        getLong(rs, "battle_rating"),
                        getInt(rs, "battle_match_count"),
                        getInt(rs, "first_solved_problem_count")));
    }

    public List<RankingDashboardResponse.TagStat> findTagStats(Long memberId) {
        String sql = """
                select
                    t.name as tag,
                    count(*) as submission_count,
                    count(*) filter (where s.result = 'AC') as ac_submission_count,
                    count(distinct coalesce(s.problem_id, br.problem_id)) filter (where s.result = 'AC') as solved_count
                from submissions s
                left join battle_rooms br on br.id = s.room_id
                join problem_tag_connect ptc on ptc.problem_id = coalesce(s.problem_id, br.problem_id)
                join tags t on t.id = ptc.tag_id
                where s.user_id = :memberId
                  and coalesce(s.problem_id, br.problem_id) is not null
                group by t.id, t.name
                order by solved_count desc, submission_count desc, t.name asc
                limit 10
                """;

        return jdbcTemplate.query(sql, Map.of("memberId", memberId), (rs, rowNum) -> {
            long submissionCount = getLong(rs, "submission_count");
            long acSubmissionCount = getLong(rs, "ac_submission_count");
            int accuracy =
                    submissionCount == 0 ? 0 : (int) Math.round((double) acSubmissionCount * 100.0d / submissionCount);
            return new RankingDashboardResponse.TagStat(
                    rs.getString("tag"), getLong(rs, "solved_count"), submissionCount, accuracy);
        });
    }

    public RankingDashboardResponse.ReviewSummary findReviewSummary(Long memberId, LocalDateTime now) {
        String sql = """
                select
                    coalesce(sum(case when next_review_at <= :now then 1 else 0 end), 0) as due_today_count,
                    coalesce(sum(case when next_review_at > :now then 1 else 0 end), 0) as upcoming_count
                from review_schedule
                where user_id = :memberId
                """;

        SqlParameterSource params =
                new MapSqlParameterSource().addValue("memberId", memberId).addValue("now", now);
        return jdbcTemplate.queryForObject(
                sql,
                params,
                (rs, rowNum) -> new RankingDashboardResponse.ReviewSummary(
                        getLong(rs, "due_today_count"), getLong(rs, "upcoming_count")));
    }

    private <T> Optional<T> queryForOptional(String sql, Map<String, ?> params, RowMapper<T> rowMapper) {
        return jdbcTemplate.query(sql, params, rowMapper).stream().findFirst();
    }

    // 최근 표본에서 final_rank <= 2 인 비율을 퍼센트로 환산한다.
    private int calculateTop2Rate(List<RecentBattleWindowRow> recentBattleWindow) {
        if (recentBattleWindow == null || recentBattleWindow.isEmpty()) {
            return 0;
        }

        long top2Count = recentBattleWindow.stream()
                .map(RecentBattleWindowRow::finalRank)
                .filter(rank -> rank != null && rank <= 2)
                .count();
        return (int) Math.round((double) top2Count * 100.0d / recentBattleWindow.size());
    }

    private static long getLong(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? 0L : value.longValue();
    }

    private static int getInt(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? 0 : value.intValue();
    }

    private static LocalDateTime getLocalDateTime(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toLocalDateTime();
    }

    public record MemberRankRow(
            Long memberId,
            String nickname,
            String tier,
            long score,
            long battleRating,
            int battleMatchCount,
            int firstSolvedProblemCount,
            long rank,
            long totalCount) {}

    public record BattleSummary(long battleMatchCount, int top2Rate, int top2SampleSize, long scoreDeltaTotal) {}

    public record BattleTrendRow(LocalDateTime occurredAt, long delta) {}

    public record NearbyRankingRow(
            long rank,
            Long memberId,
            String nickname,
            String tier,
            long battleRating,
            int battleMatchCount,
            int firstSolvedProblemCount) {}

    public record TierSourceRow(String tier, long battleRating, int battleMatchCount, int firstSolvedProblemCount) {}

    private record RecentBattleWindowRow(Integer finalRank, long scoreDelta) {}
}
