package com.back.global.init;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseIndexInitializer {

    private final JdbcTemplate jdbcTemplate;

    /**
     * ddl-auto: update 가 테이블 스키마를 먼저 반영한 뒤 실행되도록 ApplicationReadyEvent 사용.
     * IF NOT EXISTS 로 멱등성 보장 — 서버 재시작 시 중복 실행돼도 안전.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void createIndexes() {
        // 컬럼명 리팩터 이후에도 기존 데이터(solo_*)를 새 컬럼(first_solve_*)로 보존한다.
        jdbcTemplate.execute("""
                ALTER TABLE member_rating_profiles
                ADD COLUMN IF NOT EXISTS first_solve_score INTEGER
                """);
        jdbcTemplate.execute("""
                ALTER TABLE member_rating_profiles
                ADD COLUMN IF NOT EXISTS first_solved_problem_count INTEGER
                """);
        jdbcTemplate.execute("""
                DO $$
                BEGIN
                    IF EXISTS (
                        SELECT 1
                        FROM information_schema.columns
                        WHERE table_name = 'member_rating_profiles' AND column_name = 'solo_score'
                    ) THEN
                        EXECUTE 'UPDATE member_rating_profiles
                                 SET first_solve_score = COALESCE(first_solve_score, solo_score, 0)';
                    ELSE
                        EXECUTE 'UPDATE member_rating_profiles
                                 SET first_solve_score = COALESCE(first_solve_score, 0)';
                    END IF;
                END $$;
                """);
        // 새 레이팅 체계 기본값: SR/AP는 0점에서 시작한다.
        jdbcTemplate.execute("""
                UPDATE member_rating_profiles
                SET battle_rating = CASE
                    WHEN battle_rating IS NULL THEN 0
                    ELSE battle_rating
                END
                """);
        jdbcTemplate.execute("""
                UPDATE member_rating_profiles
                SET tier_score = COALESCE(
                    tier_score,
                    COALESCE(battle_rating, 0)
                )
                """);
        jdbcTemplate.execute("""
                DO $$
                BEGIN
                    IF EXISTS (
                        SELECT 1
                        FROM information_schema.columns
                        WHERE table_name = 'member_rating_profiles' AND column_name = 'solo_first_ac_count'
                    ) THEN
                        EXECUTE 'UPDATE member_rating_profiles
                                 SET first_solved_problem_count = COALESCE(first_solved_problem_count, solo_first_ac_count, 0)';
                    ELSE
                        EXECUTE 'UPDATE member_rating_profiles
                                 SET first_solved_problem_count = COALESCE(first_solved_problem_count, 0)';
                    END IF;
                END $$;
                """);

        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_one_playing_per_member
                ON battle_participants (user_id)
                WHERE status = 'PLAYING'
                """);
        jdbcTemplate.execute("""
                DROP INDEX IF EXISTS idx_member_rating_profiles_solo_score
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_member_rating_profiles_tier_score
                ON member_rating_profiles (tier_score DESC, member_id ASC)
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_member_rating_profiles_battle_rating
                ON member_rating_profiles (battle_rating DESC, member_id ASC)
                """);
        jdbcTemplate.execute("""
                DROP INDEX IF EXISTS idx_member_rating_profiles_hard_battle_rating
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_member_rating_profiles_first_solve_score
                ON member_rating_profiles (first_solve_score DESC, member_id ASC)
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_mpfs_member_id
                ON member_problem_first_solves (member_id)
                """);
        jdbcTemplate.execute("""
                CREATE INDEX IF NOT EXISTS idx_mpfs_first_solved_at
                ON member_problem_first_solves (first_solved_at DESC)
                """);
        log.info("DB index 확인 완료 - uq_one_playing_per_member, idx_member_rating_profiles_tier_score,"
                + " idx_member_rating_profiles_battle_rating,"
                + " idx_member_rating_profiles_first_solve_score, idx_mpfs_member_id, idx_mpfs_first_solved_at");
    }
}
