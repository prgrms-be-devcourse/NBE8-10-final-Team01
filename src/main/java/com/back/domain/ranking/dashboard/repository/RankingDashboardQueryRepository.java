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
public class RankingDashboardQueryRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Optional<MemberRankRow> findMemberRank(Long memberId) {
        String sql = """
                with ranked as (
                    select
                        m.id as member_id,
                        m.nickname,
                        m.tier,
                        coalesce(m.score, 0) as score,
                        row_number() over (order by coalesce(m.score, 0) desc, m.id asc) as rank_no,
                        count(*) over () as total_count
                    from members m
                )
                select member_id, nickname, tier, score, rank_no, total_count
                from ranked
                where member_id = :memberId
                """;

        return queryForOptional(
                sql,
                Map.of("memberId", memberId),
                (rs, rowNum) -> new MemberRankRow(
                        rs.getLong("member_id"),
                        rs.getString("nickname"),
                        rs.getString("tier"),
                        getLong(rs, "score"),
                        getLong(rs, "rank_no"),
                        getLong(rs, "total_count")));
    }

    public BattleSummary findBattleSummary(Long memberId, int top2WindowSize) {
        String summarySql = """
                select
                    count(*) as battle_match_count,
                    coalesce(sum(bp.score_delta), 0) as score_delta_total
                from battle_participants bp
                join battle_rooms br on br.id = bp.room_id
                where bp.user_id = :memberId
                  and upper(br.status) = 'FINISHED'
                """;

        BattleSummary totalSummary = jdbcTemplate.queryForObject(
                summarySql,
                Map.of("memberId", memberId),
                (rs, rowNum) ->
                        new BattleSummary(getLong(rs, "battle_match_count"), 0, getLong(rs, "score_delta_total")));

        String recentRanksSql = """
                select bp.final_rank
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
        List<Integer> recentRanks =
                jdbcTemplate.query(recentRanksSql, params, (rs, rowNum) -> getInt(rs, "final_rank"));
        int top2Rate = calculateTop2Rate(recentRanks);

        return new BattleSummary(totalSummary.battleMatchCount(), top2Rate, totalSummary.scoreDeltaTotal());
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
                        m.tier,
                        coalesce(m.score, 0) as score,
                        row_number() over (order by coalesce(m.score, 0) desc, m.id asc) as rank_no
                    from members m
                ),
                me as (
                    select rank_no
                    from ranked
                    where member_id = :memberId
                )
                select r.rank_no, r.member_id, r.nickname, r.tier, r.score
                from ranked r
                cross join me
                where r.rank_no between me.rank_no - :radius and me.rank_no + :radius
                order by r.rank_no asc
                """;

        SqlParameterSource params =
                new MapSqlParameterSource().addValue("memberId", memberId).addValue("radius", radius);
        return jdbcTemplate.query(
                sql,
                params,
                (rs, rowNum) -> new NearbyRankingRow(
                        getLong(rs, "rank_no"),
                        rs.getLong("member_id"),
                        rs.getString("nickname"),
                        rs.getString("tier"),
                        getLong(rs, "score")));
    }

    public List<TierSourceRow> findTierSources() {
        String sql = "select tier, coalesce(score, 0) as score from members";
        return jdbcTemplate.query(sql, (rs, rowNum) -> new TierSourceRow(rs.getString("tier"), getLong(rs, "score")));
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

    private int calculateTop2Rate(List<Integer> recentRanks) {
        if (recentRanks == null || recentRanks.isEmpty()) {
            return 0;
        }

        long top2Count =
                recentRanks.stream().filter(rank -> rank != null && rank <= 2).count();
        return (int) Math.round((double) top2Count * 100.0d / recentRanks.size());
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

    public record MemberRankRow(Long memberId, String nickname, String tier, long score, long rank, long totalCount) {}

    public record BattleSummary(long battleMatchCount, int top2Rate, long scoreDeltaTotal) {}

    public record BattleTrendRow(LocalDateTime occurredAt, long delta) {}

    public record NearbyRankingRow(long rank, Long memberId, String nickname, String tier, long score) {}

    public record TierSourceRow(String tier, long score) {}
}
