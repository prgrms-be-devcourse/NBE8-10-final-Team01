package com.back.domain.ranking.dashboard.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.back.global.IntegrationTestBase;
import com.back.global.jwt.JwtProvider;

@AutoConfigureMockMvc
@Transactional
class RankingDashboardControllerTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtProvider jwtProvider;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from submissions");
        jdbcTemplate.update("delete from solo_submissions");
        jdbcTemplate.update("delete from battle_participants");
        jdbcTemplate.update("delete from battle_rooms");
        jdbcTemplate.update("delete from review_schedule");
        jdbcTemplate.update("delete from member_problem_first_solves");
        jdbcTemplate.update("delete from member_rating_profiles");
        jdbcTemplate.update("delete from problem_tag_connect");
        jdbcTemplate.update("delete from problem_language_profiles");
        jdbcTemplate.update("delete from problem_translations");
        jdbcTemplate.update("delete from test_cases");
        jdbcTemplate.update("delete from tags");
        jdbcTemplate.update("delete from problems");
        jdbcTemplate.update("delete from members");
    }

    @Test
    @DisplayName("dashboard returns raw JSON and current DB aggregates")
    void getMyDashboard_success() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        Long rank1 = insertMember("rank1@example.com", "rank1", 1700, "GOLD_4");
        Long rank2 = insertMember("rank2@example.com", "rank2", 1600, "GOLD_5");
        Long me = insertMember("me@example.com", "me", 1580, "SILVER_1");
        Long rank4 = insertMember("rank4@example.com", "rank4", 1400, "SILVER_4");
        Long rank5 = insertMember("rank5@example.com", "rank5", 1200, "BRONZE_2");

        Long problem1500 = insertProblem("P1500", "DP Basic", 1500);
        Long problem2100 = insertProblem("P2100", "DP Hard", 2100);
        Long dpTag = insertTag("DP");
        insertProblemTag(problem1500, dpTag);
        insertProblemTag(problem2100, dpTag);

        Long oldRoom = insertFinishedRoom(problem1500, now.minusDays(6));
        Long recentRoom = insertFinishedRoom(problem2100, now.minusDays(1));
        insertParticipant(oldRoom, me, "SOLVED", 1, 20, now.minusDays(6).plusMinutes(10));
        insertParticipant(recentRoom, me, "TIMEOUT", 3, -5, now.minusDays(1).plusMinutes(10));

        insertSubmission(oldRoom, me, problem1500, "AC", now.minusDays(6).plusMinutes(5));
        insertSubmission(oldRoom, me, problem1500, "WA", now.minusDays(6).plusMinutes(6));
        insertSubmission(recentRoom, me, problem2100, "AC", now.minusDays(1).plusMinutes(5));
        insertReview(me, problem1500, now.minusDays(2), now.minusMinutes(1));
        insertReview(me, problem2100, now.minusDays(1), now.plusDays(3));

        mockMvc.perform(get("/api/v1/rankings/me/dashboard").cookie(accessToken(me, "me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").doesNotExist())
                .andExpect(jsonPath("$.profile.memberId").value(me))
                .andExpect(jsonPath("$.profile.nickname").value("me"))
                .andExpect(jsonPath("$.profile.tier").value("SILVER_1"))
                .andExpect(jsonPath("$.profile.rank").value(3))
                .andExpect(jsonPath("$.profile.percentile").value(60.0))
                .andExpect(jsonPath("$.profile.score").value(1580))
                .andExpect(jsonPath("$.profile.nextTier").value("GOLD_5"))
                .andExpect(jsonPath("$.profile.battleMatchCount").value(2))
                .andExpect(jsonPath("$.profile.top2Rate").value(50))
                .andExpect(jsonPath("$.profile.scoreDeltaTotal").value(15))
                .andExpect(jsonPath("$.scoreTrend", hasSize(2)))
                .andExpect(jsonPath("$.scoreTrend[0].label").value("D-6"))
                .andExpect(jsonPath("$.scoreTrend[0].score").value(1585))
                .andExpect(jsonPath("$.scoreTrend[0].delta").value(20))
                .andExpect(jsonPath("$.scoreTrend[1].label").value("NOW"))
                .andExpect(jsonPath("$.scoreTrend[1].score").value(1580))
                .andExpect(jsonPath("$.scoreTrend[1].delta").value(-5))
                .andExpect(jsonPath("$.gateProgress[0].key").value("SCORE"))
                .andExpect(jsonPath("$.gateProgress[0].current").value(1580))
                .andExpect(jsonPath("$.gateProgress[0].target").value(1600))
                .andExpect(jsonPath("$.gateProgress[1].key").value("SOLVED_1400"))
                .andExpect(jsonPath("$.gateProgress[1].current").value(2))
                .andExpect(jsonPath("$.gateProgress[1].target").value(12))
                .andExpect(jsonPath("$.nearbyRanking", hasSize(5)))
                .andExpect(jsonPath("$.nearbyRanking[0].memberId").value(rank1))
                .andExpect(jsonPath("$.nearbyRanking[1].memberId").value(rank2))
                .andExpect(jsonPath("$.nearbyRanking[2].memberId").value(me))
                .andExpect(jsonPath("$.nearbyRanking[2].isMe").value(true))
                .andExpect(jsonPath("$.nearbyRanking[3].memberId").value(rank4))
                .andExpect(jsonPath("$.nearbyRanking[4].memberId").value(rank5))
                .andExpect(jsonPath("$.tierDistribution[2].tier").value("SILVER_1"))
                .andExpect(jsonPath("$.tierDistribution[2].percentage").value(20.0))
                .andExpect(jsonPath("$.tierDistribution[2].isMyTier").value(true))
                .andExpect(jsonPath("$.tagStats[0].tag").value("DP"))
                .andExpect(jsonPath("$.tagStats[0].solvedCount").value(2))
                .andExpect(jsonPath("$.tagStats[0].submissionCount").value(3))
                .andExpect(jsonPath("$.tagStats[0].accuracy").value(67))
                .andExpect(jsonPath("$.reviewSummary.dueTodayCount").value(1))
                .andExpect(jsonPath("$.reviewSummary.upcomingCount").value(1));
    }

    @Test
    @DisplayName("dashboard returns empty arrays and zero values for a user without history")
    void getMyDashboard_emptyUser() throws Exception {
        Long me = insertMember("empty@example.com", "empty", 0, null);

        mockMvc.perform(get("/api/v1/rankings/me/dashboard").cookie(accessToken(me, "empty")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resultCode").doesNotExist())
                .andExpect(jsonPath("$.profile.memberId").value(me))
                .andExpect(jsonPath("$.profile.tier").value("BRONZE_5"))
                .andExpect(jsonPath("$.profile.rank").value(1))
                .andExpect(jsonPath("$.profile.percentile").value(100.0))
                .andExpect(jsonPath("$.profile.battleMatchCount").value(0))
                .andExpect(jsonPath("$.profile.top2Rate").value(0))
                .andExpect(jsonPath("$.scoreTrend", hasSize(1)))
                .andExpect(jsonPath("$.scoreTrend[0].label").value("NOW"))
                .andExpect(jsonPath("$.scoreTrend[0].score").value(0))
                .andExpect(jsonPath("$.scoreTrend[0].delta").value(0))
                .andExpect(jsonPath("$.gateProgress[0].key").value("SCORE"))
                .andExpect(jsonPath("$.gateProgress[0].target").value(1060))
                .andExpect(jsonPath("$.nearbyRanking", hasSize(1)))
                .andExpect(jsonPath("$.nearbyRanking[0].isMe").value(true))
                .andExpect(jsonPath("$.tierDistribution[0].tier").value("BRONZE_5"))
                .andExpect(jsonPath("$.tierDistribution[0].isMyTier").value(true))
                .andExpect(jsonPath("$.tagStats", hasSize(0)))
                .andExpect(jsonPath("$.reviewSummary.dueTodayCount").value(0))
                .andExpect(jsonPath("$.reviewSummary.upcomingCount").value(0));
    }

    private MockCookie accessToken(Long memberId, String nickname) {
        return new MockCookie(
                "accessToken", jwtProvider.createToken(memberId, nickname + "@example.com", nickname, "ROLE_USER"));
    }

    private Long insertMember(String email, String nickname, long score, String tier) {
        return jdbcTemplate.queryForObject("""
                insert into members (id, email, nickname, password, score, tier, role, created_at)
                values (nextval('member_id_seq'), ?, ?, 'password', ?, ?, 'USER', now())
                returning id
                """, Long.class, email, nickname, score, tier);
    }

    private Long insertProblem(String sourceProblemId, String title, int difficultyRating) {
        return jdbcTemplate.queryForObject("""
                insert into problems (
                    id, source_problem_id, title, content, difficulty, difficulty_rating,
                    time_limit_ms, memory_limit_mb, input_mode, judge_type, created_at
                )
                values (
                    nextval('problem_id_seq'), ?, ?, 'content', 'MEDIUM', ?,
                    1000, 256, 'STDIO', 'EXACT', now()
                )
                returning id
                """, Long.class, sourceProblemId, title, difficultyRating);
    }

    private Long insertTag(String name) {
        return jdbcTemplate.queryForObject(
                "insert into tags (id, name, created_at) values (nextval('tag_id_seq'), ?, now()) returning id",
                Long.class,
                name);
    }

    private void insertProblemTag(Long problemId, Long tagId) {
        jdbcTemplate.update(
                "insert into problem_tag_connect (id, problem_id, tag_id, created_at) "
                        + "values (nextval('problem_tag_id_seq'), ?, ?, now())",
                problemId,
                tagId);
    }

    private Long insertFinishedRoom(Long problemId, LocalDateTime occurredAt) {
        return jdbcTemplate.queryForObject(
                """
                insert into battle_rooms (
                    id, version, problem_id, status, max_players, timer_end, started_at, created_at
                )
                values (nextval('battle_room_id_seq'), 0, ?, 'FINISHED', 4, ?, ?, ?)
                returning id
                """, Long.class, problemId, occurredAt.plusMinutes(30), occurredAt, occurredAt);
    }

    private void insertParticipant(
            Long roomId, Long memberId, String status, int finalRank, long scoreDelta, LocalDateTime finishTime) {
        jdbcTemplate.update("""
                insert into battle_participants (
                    id, room_id, user_id, status, final_rank, score_delta,
                    finish_time, is_result_checked, created_at
                )
                values (nextval('participant_id_seq'), ?, ?, ?, ?, ?, ?, true, ?)
                """, roomId, memberId, status, finalRank, scoreDelta, finishTime, finishTime);
    }

    private void insertSubmission(
            Long roomId, Long memberId, Long problemId, String result, LocalDateTime submittedAt) {
        jdbcTemplate.update("""
                insert into submissions (
                    id, room_id, user_id, problem_id, code, language,
                    result, passed_count, total_count, created_at
                )
                values (nextval('submission_id_seq'), ?, ?, ?, 'code', 'java', ?, 1, 1, ?)
                """, roomId, memberId, problemId, result, submittedAt);
    }

    private void insertReview(Long memberId, Long problemId, LocalDateTime solvedAt, LocalDateTime nextReviewAt) {
        jdbcTemplate.update("""
                insert into review_schedule (
                    id, user_id, problem_id, solved_at, next_review_at, review_count, is_review_required, created_at
                )
                values (nextval('review_schedule_id_seq'), ?, ?, ?, ?, 1, true, now())
                """, memberId, problemId, solvedAt, nextReviewAt);
    }
}
