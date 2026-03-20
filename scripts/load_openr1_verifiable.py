#!/usr/bin/env python3
"""
open-r1/codeforces(verifiable)를 로컬 PostgreSQL 테이블로 적재한다.
  - problems
  - tags
  - problem_tag_connect
  - test_cases

서비스 안정성을 위해 다음 조건으로 필터링한다.
  - executable == true
  - interactive 문제 제외(interaction_format/is_interactive)
  - input_mode == STDIO (--allow-file-mode 미사용 시)
  - official_tests 존재
  - 과도하게 큰 official_tests payload 제외
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from datetime import datetime
from typing import Any
from urllib.parse import quote

import requests

psycopg = None


HF_BASE = "https://datasets-server.huggingface.co"
HF_DATASET = "open-r1/codeforces"
HF_CONFIG = "verifiable"
HF_SPLIT = "train"
# open-r1 rows API에서 `tags` 필드를 제공한다. (list[str])


def ensure_psycopg() -> None:
    global psycopg
    if psycopg is not None:
        return
    try:
        import psycopg as _psycopg
    except ImportError as exc:  # pragma: no cover
        raise SystemExit(
            "psycopg is required for DB write. Install with: pip install 'psycopg[binary]' requests"
        ) from exc
    psycopg = _psycopg


def map_difficulty(rating: int | None) -> str:
    """rating 값을 EASY/MEDIUM/HARD 난이도로 매핑한다."""
    if rating is None or rating < 1400:
        return "EASY"
    if rating < 2200:
        return "MEDIUM"
    return "HARD"


def build_content(row: dict[str, Any]) -> str:
    """문제 본문(설명/노트)만 content 컬럼으로 저장한다."""
    chunks = [row.get("description") or ""]
    note = row.get("note")
    if note:
        chunks.append(f"[Note]\n{note}")
    return "\n\n".join(chunks).strip()


def get_rows(offset: int, length: int) -> list[dict[str, Any]]:
    """HF rows API에서 verifiable/train 데이터를 chunk 단위로 조회한다."""
    url = (
        f"{HF_BASE}/rows?dataset={quote(HF_DATASET, safe='')}"
        f"&config={quote(HF_CONFIG, safe='')}"
        f"&split={quote(HF_SPLIT, safe='')}"
        f"&offset={offset}&length={length}"
    )
    res = requests.get(url, timeout=120)
    res.raise_for_status()
    body = res.json()
    return [item["row"] for item in body.get("rows", [])]


def get_or_create_tag(
    cur: psycopg.Cursor[Any], cache: dict[str, int], name: str, tag_id_sequence: str | None
) -> int:
    """태그를 upsert 성격으로 조회/생성한다."""
    cached = cache.get(name)
    if cached is not None:
        return cached

    cur.execute("SELECT id FROM tags WHERE name = %s", (name,))
    row = cur.fetchone()
    if row:
        tag_id = int(row[0])
        cache[name] = tag_id
        return tag_id

    if tag_id_sequence:
        cur.execute(
            f"INSERT INTO tags (id, name) VALUES (nextval('{tag_id_sequence}'), %s) RETURNING id",
            (name,),
        )
    else:
        cur.execute("INSERT INTO tags (name) VALUES (%s) RETURNING id", (name,))
    tag_id = int(cur.fetchone()[0])
    cache[name] = tag_id
    return tag_id


def normalize_tags(raw_tags: Any) -> list[str]:
    """rows API의 tags 값을 일관된 list[str]로 정규화한다."""
    if raw_tags is None:
        return []
    if isinstance(raw_tags, list):
        return [str(tag).strip() for tag in raw_tags if str(tag).strip()]
    if isinstance(raw_tags, str):
        value = raw_tags.strip()
        return [value] if value else []
    return []


def problem_exists(
    cur: psycopg.Cursor[Any], source_problem_id: str | None, title: str, rating: int | None
) -> int | None:
    """중복 적재 방지를 위한 문제 존재 여부를 검사한다."""
    if source_problem_id:
        cur.execute(
            """
            SELECT id
            FROM problems
            WHERE source_problem_id = %s
            LIMIT 1
            """,
            (source_problem_id,),
        )
        row = cur.fetchone()
        if row:
            return int(row[0])

    # source_problem_id가 없는 레거시 데이터와의 호환을 위한 fallback.
    cur.execute(
        """
        SELECT id, source_problem_id
        FROM problems
        WHERE title = %s
          AND difficulty_rating IS NOT DISTINCT FROM %s
        LIMIT 1
        """,
        (title, rating),
    )
    row = cur.fetchone()
    if not row:
        return None

    problem_id = int(row[0])
    existing_source_problem_id = row[1]
    if source_problem_id and not existing_source_problem_id:
        # 레거시 행의 source_problem_id를 자연스럽게 보강한다.
        cur.execute(
            """
            UPDATE problems
            SET source_problem_id = %s
            WHERE id = %s
            """,
            (source_problem_id, problem_id),
        )

    return problem_id


def resolve_id_sequence(
    cur: psycopg.Cursor[Any], table_name: str, preferred_sequence_names: tuple[str, ...]
) -> str | None:
    """
    테이블 id 자동 생성 전략을 판별한다.

    - identity/default가 있으면 None 반환(일반 INSERT)
    - 없으면 사용할 시퀀스명을 반환(예: problem_id_seq)
    """
    cur.execute(
        """
        SELECT is_identity, column_default
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = %s
          AND column_name = 'id'
        """,
        (table_name,),
    )
    row = cur.fetchone()
    if row:
        is_identity, column_default = row
        if is_identity == "YES" or column_default:
            return None

    # Hibernate(Sequence) 전략에서 생성된 시퀀스명을 우선 탐색한다.
    candidates: list[str] = []
    for seq in preferred_sequence_names:
        candidates.append(f"public.{seq}")
        candidates.append(seq)
    for candidate in candidates:
        cur.execute("SELECT to_regclass(%s)::text", (candidate,))
        seq_name = cur.fetchone()[0]
        if seq_name:
            return str(seq_name)

    # serial/identity 계열에서 매핑된 시퀀스가 있으면 사용한다.
    cur.execute("SELECT pg_get_serial_sequence(%s, 'id')", (f"public.{table_name}",))
    serial_seq = cur.fetchone()[0]
    if serial_seq:
        return str(serial_seq)

    raise RuntimeError(
        f"Cannot resolve sequence/default for {table_name}.id. "
        "Run BackApplication once to create schema or fix id generation."
    )


def insert_problem(
    cur: psycopg.Cursor[Any], row: dict[str, Any], problem_id_sequence: str | None
) -> tuple[int, bool]:
    """problems 테이블에 1건 적재하고 (problem_id, 신규삽입여부)를 반환한다."""
    rating = row.get("rating")
    difficulty = map_difficulty(rating)
    input_mode = (row.get("input_mode") or "stdio").upper()
    checker_code = row.get("generated_checker") or None
    judge_type = "CHECKER" if checker_code else "EXACT"
    source_problem_id = (row.get("id") or "").strip() or None

    title = row.get("title") or source_problem_id or "untitled"
    existing = problem_exists(cur, source_problem_id, title, rating)
    if existing is not None:
        return existing, False

    if problem_id_sequence:
        # id 기본값이 없을 때는 시퀀스를 명시적으로 사용한다.
        cur.execute(
            f"""
            INSERT INTO problems (
                id,
                source_problem_id,
                title,
                difficulty,
                content,
                input_format,
                output_format,
                difficulty_rating,
                time_limit_ms,
                memory_limit_mb,
                input_mode,
                checker_code,
                judge_type
            ) VALUES (nextval('{problem_id_sequence}'), %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            RETURNING id
            """,
            (
                source_problem_id,
                title,
                difficulty,
                build_content(row),
                row.get("input_format") or None,
                row.get("output_format") or None,
                rating,
                int(float(row.get("time_limit") or 1) * 1000),
                int(float(row.get("memory_limit") or 256)),
                input_mode,
                checker_code,
                judge_type,
            ),
        )
    else:
        cur.execute(
            """
            INSERT INTO problems (
                source_problem_id,
                title,
                difficulty,
                content,
                input_format,
                output_format,
                difficulty_rating,
                time_limit_ms,
                memory_limit_mb,
                input_mode,
                checker_code,
                judge_type
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            RETURNING id
            """,
            (
                source_problem_id,
                title,
                difficulty,
                build_content(row),
                row.get("input_format") or None,
                row.get("output_format") or None,
                rating,
                int(float(row.get("time_limit") or 1) * 1000),
                int(float(row.get("memory_limit") or 256)),
                input_mode,
                checker_code,
                judge_type,
            ),
        )
    return int(cur.fetchone()[0]), True


def insert_problem_tags(
    cur: psycopg.Cursor[Any],
    tag_cache: dict[str, int],
    problem_id: int,
    tags: list[str],
    tag_id_sequence: str | None,
    problem_tag_connect_id_sequence: str | None,
) -> None:
    """문제-태그 연결 테이블을 채운다(중복 연결 방지)."""
    for tag in tags:
        tag_name = tag.strip()
        if not tag_name:
            continue
        tag_id = get_or_create_tag(cur, tag_cache, tag_name, tag_id_sequence)
        cur.execute(
            """
            SELECT 1
            FROM problem_tag_connect
            WHERE problem_id = %s AND tag_id = %s
            LIMIT 1
            """,
            (problem_id, tag_id),
        )
        if cur.fetchone():
            continue
        if problem_tag_connect_id_sequence:
            cur.execute(
                f"""
                INSERT INTO problem_tag_connect (id, problem_id, tag_id)
                VALUES (nextval('{problem_tag_connect_id_sequence}'), %s, %s)
                """,
                (problem_id, tag_id),
            )
        else:
            cur.execute(
                """
                INSERT INTO problem_tag_connect (problem_id, tag_id)
                VALUES (%s, %s)
                """,
                (problem_id, tag_id),
            )


def insert_test_cases(
    cur: psycopg.Cursor[Any],
    problem_id: int,
    examples: list[dict[str, str]],
    official_tests: list[dict[str, str]],
    max_tests_per_problem: int,
    test_case_id_sequence: str | None,
) -> tuple[int, int]:
    """examples는 sample, official_tests는 hidden 테스트로 분리 적재한다."""
    inserted_sample = 0
    inserted_hidden = 0

    sample_cases = examples[: max_tests_per_problem // 2]
    hidden_cases = official_tests[: max_tests_per_problem]

    for case in sample_cases:
        if test_case_id_sequence:
            cur.execute(
                f"""
                INSERT INTO test_cases (id, problem_id, input, expected_output, is_sample)
                VALUES (nextval('{test_case_id_sequence}'), %s, %s, %s, true)
                """,
                (problem_id, case.get("input", ""), case.get("output", "")),
            )
        else:
            cur.execute(
                """
                INSERT INTO test_cases (problem_id, input, expected_output, is_sample)
                VALUES (%s, %s, %s, true)
                """,
                (problem_id, case.get("input", ""), case.get("output", "")),
            )
        inserted_sample += 1

    for case in hidden_cases:
        if test_case_id_sequence:
            cur.execute(
                f"""
                INSERT INTO test_cases (id, problem_id, input, expected_output, is_sample)
                VALUES (nextval('{test_case_id_sequence}'), %s, %s, %s, false)
                """,
                (problem_id, case.get("input", ""), case.get("output", "")),
            )
        else:
            cur.execute(
                """
                INSERT INTO test_cases (problem_id, input, expected_output, is_sample)
                VALUES (%s, %s, %s, false)
                """,
                (problem_id, case.get("input", ""), case.get("output", "")),
            )
        inserted_hidden += 1

    return inserted_sample, inserted_hidden


def should_skip_row(
    row: dict[str, Any],
    allow_file_mode: bool,
    max_official_tests_bytes: int,
) -> tuple[bool, str]:
    """운영 안전성 기준으로 적재 제외 여부를 판정한다."""
    if not row.get("executable", False):
        return True, "not_executable"

    # open-r1/codeforces는 is_interactive 필드가 없고 interaction_format이 채워진다.
    # 두 케이스를 모두 방어적으로 처리해서 인터랙티브 문제를 적재 대상에서 제외한다.
    interaction_format = row.get("interaction_format")
    has_interaction = interaction_format is not None and str(interaction_format).strip() != ""
    if bool(row.get("is_interactive")) or has_interaction:
        return True, "interactive"

    input_mode = (row.get("input_mode") or "stdio").lower()
    if not allow_file_mode and input_mode != "stdio":
        return True, "non_stdio"

    tests = row.get("official_tests") or []
    if not tests:
        return True, "no_official_tests"

    test_bytes = len(json.dumps(tests, ensure_ascii=False))
    if test_bytes > max_official_tests_bytes:
        return True, "too_large_tests"

    return False, ""


def default_dsn() -> str:
    """docker-compose 환경변수(DB_*)를 기준으로 기본 DSN을 만든다."""
    host = os.getenv("DB_HOST", "localhost")
    port = os.getenv("DB_PORT", "5432")
    name = os.getenv("DB_NAME", "back")
    user = os.getenv("DB_USERNAME", "back")
    password = os.getenv("DB_PASSWORD", "back1234")
    return f"postgresql://{user}:{password}@{host}:{port}/{name}"


def parse_args() -> argparse.Namespace:
    """CLI 옵션 파싱."""
    parser = argparse.ArgumentParser(description="Load open-r1 verifiable into PostgreSQL.")
    parser.add_argument(
        "--dsn",
        default=os.getenv("LOAD_DSN", default_dsn()),
        help="PostgreSQL DSN",
    )
    parser.add_argument("--offset", type=int, default=0)
    parser.add_argument("--limit", type=int, default=300)
    parser.add_argument("--chunk-size", type=int, default=50)
    parser.add_argument("--max-tests-per-problem", type=int, default=30)
    parser.add_argument("--max-official-tests-bytes", type=int, default=300_000)
    parser.add_argument("--allow-file-mode", action="store_true")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument(
        "--truncate",
        action="store_true",
        help="TRUNCATE target tables before load (dangerous).",
    )
    return parser.parse_args()


def main() -> int:
    """엔드투엔드 적재 실행 진입점."""
    args = parse_args()
    tag_cache: dict[str, int] = {}
    stats = {
        "rows_fetched": 0,
        "rows_loaded": 0,
        "problems_inserted": 0,
        "skipped_existing_problem": 0,
        "sample_tests": 0,
        "hidden_tests": 0,
        "skipped_not_executable": 0,
        "skipped_interactive": 0,
        "skipped_non_stdio": 0,
        "skipped_no_official_tests": 0,
        "skipped_too_large_tests": 0,
    }

    if args.dry_run:
        print("dry-run mode: no DB write")

    conn: psycopg.Connection[Any] | None = None
    cur: psycopg.Cursor[Any] | None = None
    problem_id_sequence: str | None = None
    tag_id_sequence: str | None = None
    test_case_id_sequence: str | None = None
    problem_tag_connect_id_sequence: str | None = None

    try:
        if not args.dry_run:
            ensure_psycopg()
            conn = psycopg.connect(args.dsn)
            conn.autocommit = False
            cur = conn.cursor()
            problem_id_sequence = resolve_id_sequence(cur, "problems", ("problem_id_seq",))
            tag_id_sequence = resolve_id_sequence(cur, "tags", ("tag_id_seq",))
            test_case_id_sequence = resolve_id_sequence(cur, "test_cases", ("test_case_id_seq",))
            problem_tag_connect_id_sequence = resolve_id_sequence(
                cur, "problem_tag_connect", ("problem_tag_id_seq",)
            )
            if args.truncate:
                # problems를 참조하는 연관 테이블(FK)까지 함께 비우기 위해 CASCADE를 사용한다.
                cur.execute("TRUNCATE TABLE test_cases, problem_tag_connect, tags, problems RESTART IDENTITY CASCADE")
                print("truncated tables")

        remaining = args.limit
        next_offset = args.offset

        while remaining > 0:
            length = min(args.chunk_size, remaining)
            rows = get_rows(next_offset, length)
            if not rows:
                break

            for row in rows:
                stats["rows_fetched"] += 1
                skip, reason = should_skip_row(
                    row,
                    allow_file_mode=args.allow_file_mode,
                    max_official_tests_bytes=args.max_official_tests_bytes,
                )
                if skip:
                    stats[f"skipped_{reason}"] += 1
                    continue

                if args.dry_run:
                    stats["rows_loaded"] += 1
                    continue

                # dry-run 모드가 아니면 DB 커서는 항상 존재한다.
                assert cur is not None
                problem_id, inserted = insert_problem(cur, row, problem_id_sequence)
                if not inserted:
                    stats["skipped_existing_problem"] += 1
                    continue
                stats["problems_inserted"] += 1

                insert_problem_tags(
                    cur,
                    tag_cache,
                    problem_id,
                    normalize_tags(row.get("tags")),
                    tag_id_sequence,
                    problem_tag_connect_id_sequence,
                )
                sample_n, hidden_n = insert_test_cases(
                    cur,
                    problem_id,
                    row.get("examples") or [],
                    row.get("official_tests") or [],
                    args.max_tests_per_problem,
                    test_case_id_sequence,
                )
                stats["sample_tests"] += sample_n
                stats["hidden_tests"] += hidden_n
                stats["rows_loaded"] += 1

            next_offset += len(rows)
            remaining -= len(rows)

        if conn is not None:
            conn.commit()

    except Exception:
        if conn is not None:
            conn.rollback()
        raise
    finally:
        if cur is not None:
            cur.close()
        if conn is not None:
            conn.close()

    now = datetime.now().isoformat(timespec="seconds")
    print(f"[{now}] load summary")
    for k, v in stats.items():
        print(f"- {k}: {v}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
