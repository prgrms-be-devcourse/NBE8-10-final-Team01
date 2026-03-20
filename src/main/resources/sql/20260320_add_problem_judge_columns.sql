-- PostgreSQL 마이그레이션 스크립트(수동 실행)
-- open-r1/codeforces 적재를 위한 채점 관련 컬럼을 추가한다.

ALTER TABLE problems
    ADD COLUMN IF NOT EXISTS source_problem_id VARCHAR(50),
    ADD COLUMN IF NOT EXISTS difficulty_rating INTEGER,
    ADD COLUMN IF NOT EXISTS input_format TEXT,
    ADD COLUMN IF NOT EXISTS output_format TEXT,
    ADD COLUMN IF NOT EXISTS input_mode VARCHAR(10) NOT NULL DEFAULT 'STDIO',
    ADD COLUMN IF NOT EXISTS checker_code TEXT,
    ADD COLUMN IF NOT EXISTS judge_type VARCHAR(20) NOT NULL DEFAULT 'EXACT';

-- 원본 문제 식별자의 중복 입력을 방지한다(NULL은 허용).
CREATE UNIQUE INDEX IF NOT EXISTS uq_problems_source_problem_id ON problems (source_problem_id);

-- 기존 difficulty 값을 EASY/MEDIUM/HARD 3단계로 정규화한다.
UPDATE problems
SET difficulty = CASE
    WHEN difficulty_rating IS NULL OR difficulty_rating < 1400 THEN 'EASY'
    WHEN difficulty_rating < 2200 THEN 'MEDIUM'
    ELSE 'HARD'
END;

-- 입력 모드/채점 방식 제약을 DB 레벨에서 보장한다.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_problems_input_mode'
    ) THEN
        ALTER TABLE problems
            ADD CONSTRAINT ck_problems_input_mode
            CHECK (input_mode IN ('STDIO', 'FILE'));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_problems_judge_type'
    ) THEN
        ALTER TABLE problems
            ADD CONSTRAINT ck_problems_judge_type
            CHECK (judge_type IN ('EXACT', 'CHECKER'));
    END IF;
END $$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_problems_difficulty'
    ) THEN
        ALTER TABLE problems
            ADD CONSTRAINT ck_problems_difficulty
            CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD'));
    END IF;
END $$;

-- 난이도 기반 목록 조회/필터 성능을 위한 인덱스.
CREATE INDEX IF NOT EXISTS idx_problems_difficulty_rating ON problems (difficulty_rating);
