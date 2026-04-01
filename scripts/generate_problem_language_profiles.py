#!/usr/bin/env python3
"""
problems를 기준으로 problem_language_profiles를 생성/갱신한다.

기본 동작:
- 문제 제목 기반으로 언어별 starter 코드를 자동 생성
- (문제ID, 언어) 유니크 키 기준 upsert

확장 동작:
- --overrides-json 파일로 특정 문제의 starter 코드를 언어별로 override 가능
  예시 구조:
  {
    "problem_id": {
      "9951": {
        "java": "class Solution { ... }",
        "python3": "class Solution: ..."
      }
    },
    "source_problem_id": {
      "852/A": {
        "java": "..."
      }
    }
  }
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

psycopg = None

DEFAULT_LANGUAGES = ["python3", "java", "c", "cpp17", "javascript"]


@dataclass
class SequenceBlockAllocator:
    """시퀀스 증가폭 단위로 id 블록을 예약하고 로컬에서 순차 배정한다."""

    sequence_name: str
    block_size: int
    next_candidate: int | None = None
    block_end: int | None = None

    def allocate(self, cur: psycopg.Cursor[Any]) -> int:
        if self.next_candidate is None or self.block_end is None or self.next_candidate > self.block_end:
            cur.execute("SELECT nextval(%s::regclass)", (self.sequence_name,))
            block_start = int(cur.fetchone()[0])
            self.next_candidate = block_start
            self.block_end = block_start + self.block_size - 1

        allocated_id = self.next_candidate
        self.next_candidate += 1
        return allocated_id


def ensure_psycopg() -> None:
    global psycopg
    if psycopg is not None:
        return
    try:
        import psycopg as _psycopg
    except ImportError as exc:  # pragma: no cover
        raise SystemExit(
            "psycopg is required. Install with: pip install 'psycopg[binary]'"
        ) from exc
    psycopg = _psycopg


def default_dsn() -> str:
    host = os.getenv("DB_HOST", "localhost")
    port = os.getenv("DB_PORT", "5432")
    name = os.getenv("DB_NAME", "back")
    user = os.getenv("DB_USERNAME", "back")
    password = os.getenv("DB_PASSWORD")
    if not password:
        raise ValueError(
            "DB_PASSWORD is required. Set it in .env and export it, or pass --dsn/LOAD_DSN."
        )
    return f"postgresql://{user}:{password}@{host}:{port}/{name}"


def resolve_id_sequence(
    cur: psycopg.Cursor[Any], table_name: str, preferred_sequence_names: tuple[str, ...]
) -> str | None:
    """
    테이블 id 자동 생성 전략을 판별한다.

    - identity/default가 있으면 None 반환(일반 INSERT)
    - 없으면 사용할 시퀀스명을 반환(예: problem_language_profile_id_seq)
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

    candidates: list[str] = []
    for seq in preferred_sequence_names:
        candidates.append(f"public.{seq}")
        candidates.append(seq)
    for candidate in candidates:
        cur.execute("SELECT to_regclass(%s)::text", (candidate,))
        seq_name = cur.fetchone()[0]
        if seq_name:
            return str(seq_name)

    cur.execute("SELECT pg_get_serial_sequence(%s, 'id')", (f"public.{table_name}",))
    serial_seq = cur.fetchone()[0]
    if serial_seq:
        return str(serial_seq)

    raise RuntimeError(
        f"Cannot resolve sequence/default for {table_name}.id. "
        "Run BackApplication once to create schema or fix id generation."
    )


def resolve_sequence_increment(cur: psycopg.Cursor[Any], sequence_name: str) -> int:
    """시퀀스 increment 값을 조회한다."""
    cur.execute(
        """
        SELECT seqincrement
        FROM pg_catalog.pg_sequence
        WHERE seqrelid = %s::regclass
        """,
        (sequence_name,),
    )
    row = cur.fetchone()
    if not row or row[0] is None:
        raise RuntimeError(f"Cannot resolve increment for sequence: {sequence_name}")

    increment = int(row[0])
    if increment <= 0:
        raise RuntimeError(f"Invalid increment {increment} for sequence: {sequence_name}")
    return increment


def slugify(text: str, max_len: int = 40) -> str:
    slug = re.sub(r"[^a-z0-9]+", "_", text.lower()).strip("_")
    if not slug:
        slug = "problem"
    if slug[0].isdigit():
        slug = f"p_{slug}"
    return slug[:max_len]


def to_camel_case(text: str, max_len: int = 50) -> str:
    parts = re.findall(r"[A-Za-z0-9]+", text)
    if not parts:
        return "Problem"
    camel = "".join(part[0].upper() + part[1:] for part in parts)
    if camel[0].isdigit():
        camel = f"P{camel}"
    return camel[:max_len]


def build_default_starter(language: str, title: str) -> str:
    safe_title = title.replace('"', "'")
    fn_slug = slugify(safe_title)
    fn_camel = to_camel_case(safe_title)

    templates: dict[str, str] = {
        "python3": (
            f"def solve_{fn_slug}():\n"
            f"    \"\"\"Problem: {safe_title}\"\"\"\n"
            "    # TODO: implement\n"
            "    pass\n\n"
            "if __name__ == \"__main__\":\n"
            f"    solve_{fn_slug}()\n"
        ),
        "java": (
            "import java.io.*;\n"
            "import java.util.*;\n\n"
            "public class Main {\n"
            f"    static void solve{fn_camel}() throws Exception {{\n"
            f"        // Problem: {safe_title}\n"
            "        // TODO: implement\n"
            "    }\n\n"
            "    public static void main(String[] args) throws Exception {\n"
            f"        solve{fn_camel}();\n"
            "    }\n"
            "}\n"
        ),
        "c": (
            "#include <stdio.h>\n\n"
            f"void solve_{fn_slug}(void) {{\n"
            f"    // Problem: {safe_title}\n"
            "    // TODO: implement\n"
            "}\n\n"
            "int main(void) {\n"
            f"    solve_{fn_slug}();\n"
            "    return 0;\n"
            "}\n"
        ),
        "cpp17": (
            "#include <bits/stdc++.h>\n"
            "using namespace std;\n\n"
            f"void solve_{fn_slug}() {{\n"
            f"    // Problem: {safe_title}\n"
            "    // TODO: implement\n"
            "}\n\n"
            "int main() {\n"
            "    ios::sync_with_stdio(false);\n"
            "    cin.tie(nullptr);\n"
            f"    solve_{fn_slug}();\n"
            "    return 0;\n"
            "}\n"
        ),
        "javascript": (
            "'use strict';\n\n"
            f"function solve{fn_camel}(input) {{\n"
            f"  // Problem: {safe_title}\n"
            "  // TODO: implement\n"
            "  return '';\n"
            "}\n\n"
            "const fs = require('fs');\n"
            "const input = fs.readFileSync(0, 'utf8');\n"
            f"process.stdout.write(String(solve{fn_camel}(input)));\n"
        ),
    }

    starter = templates.get(language)
    if starter is None:
        raise ValueError(f"Unsupported language: {language}")
    return starter


def load_overrides(path: str | None) -> dict[str, dict[str, dict[str, str]]]:
    if not path:
        return {"problem_id": {}, "source_problem_id": {}}

    payload = json.loads(Path(path).read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("overrides json must be an object")

    by_problem_id = payload.get("problem_id") or {}
    by_source_id = payload.get("source_problem_id") or {}

    if not isinstance(by_problem_id, dict) or not isinstance(by_source_id, dict):
        raise ValueError("problem_id/source_problem_id must be objects")

    return {
        "problem_id": by_problem_id,
        "source_problem_id": by_source_id,
    }


def resolve_starter(
    language: str,
    title: str,
    problem_id: int,
    source_problem_id: str | None,
    overrides: dict[str, dict[str, dict[str, str]]],
) -> str:
    by_problem_id = overrides.get("problem_id", {})
    by_source = overrides.get("source_problem_id", {})

    problem_override = by_problem_id.get(str(problem_id))
    if isinstance(problem_override, dict):
        starter = problem_override.get(language)
        if isinstance(starter, str) and starter.strip():
            return starter

    if source_problem_id:
        source_override = by_source.get(source_problem_id)
        if isinstance(source_override, dict):
            starter = source_override.get(language)
            if isinstance(starter, str) and starter.strip():
                return starter

    return build_default_starter(language, title)


def upsert_profile(
    cur: psycopg.Cursor[Any],
    id_allocator: SequenceBlockAllocator | None,
    problem_id: int,
    language_code: str,
    starter_code: str,
    is_default: bool,
) -> None:
    # update가 먼저 성공하면 id를 새로 소비하지 않는다.
    cur.execute(
        """
        UPDATE problem_language_profiles
        SET starter_code = %s,
            is_default = %s
        WHERE problem_id = %s
          AND language_code = %s
        """,
        (starter_code, is_default, problem_id, language_code),
    )
    if cur.rowcount:
        return

    if id_allocator:
        allocated_id = id_allocator.allocate(cur)
        cur.execute(
            """
            INSERT INTO problem_language_profiles
                (id, problem_id, language_code, starter_code, is_default)
            VALUES
                (%s, %s, %s, %s, %s)
            ON CONFLICT (problem_id, language_code)
            DO UPDATE SET
                starter_code = EXCLUDED.starter_code,
                is_default = EXCLUDED.is_default
            """,
            (allocated_id, problem_id, language_code, starter_code, is_default),
        )
    else:
        cur.execute(
            """
            INSERT INTO problem_language_profiles
                (problem_id, language_code, starter_code, is_default)
            VALUES
                (%s, %s, %s, %s)
            ON CONFLICT (problem_id, language_code)
            DO UPDATE SET
                starter_code = EXCLUDED.starter_code,
                is_default = EXCLUDED.is_default
            """,
            (problem_id, language_code, starter_code, is_default),
        )


def parse_languages(raw: str) -> list[str]:
    languages = [token.strip() for token in raw.split(",") if token.strip()]
    if not languages:
        raise ValueError("--languages must include at least one language")
    return languages


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate starter codes into problem_language_profiles")
    parser.add_argument("--dsn", default=os.getenv("LOAD_DSN"), help="PostgreSQL DSN")
    parser.add_argument(
        "--languages",
        default=",".join(DEFAULT_LANGUAGES),
        help="Comma-separated language codes (default: python3,java,c,cpp17,javascript)",
    )
    parser.add_argument(
        "--default-language",
        default="python3",
        help="Language code used as default starter (default: python3)",
    )
    parser.add_argument(
        "--problem-ids",
        default="",
        help="Comma-separated problem ids to generate. Empty means all problems.",
    )
    parser.add_argument(
        "--limit",
        type=int,
        default=0,
        help="Generate only latest N problems (0 means no limit)",
    )
    parser.add_argument(
        "--truncate",
        action="store_true",
        help="TRUNCATE problem_language_profiles before generation",
    )
    parser.add_argument(
        "--overrides-json",
        default="",
        help="Optional JSON file path for per-problem overrides",
    )
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def build_problem_query(problem_ids: list[int], limit: int) -> tuple[str, tuple[Any, ...]]:
    if problem_ids:
        placeholders = ",".join(["%s"] * len(problem_ids))
        return (
            f"""
            SELECT id, source_problem_id, title
            FROM problems
            WHERE id IN ({placeholders})
            ORDER BY id ASC
            """,
            tuple(problem_ids),
        )

    if limit > 0:
        return (
            """
            SELECT id, source_problem_id, title
            FROM problems
            ORDER BY id DESC
            LIMIT %s
            """,
            (limit,),
        )

    return (
        """
        SELECT id, source_problem_id, title
        FROM problems
        ORDER BY id ASC
        """,
        tuple(),
    )


def main() -> int:
    args = parse_args()

    if not args.dsn:
        try:
            args.dsn = default_dsn()
        except ValueError as exc:
            print(str(exc), file=sys.stderr)
            return 2

    try:
        languages = parse_languages(args.languages)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2

    if args.default_language not in languages:
        print("--default-language must be included in --languages", file=sys.stderr)
        return 2

    problem_ids: list[int] = []
    if args.problem_ids.strip():
        try:
            problem_ids = [int(token.strip()) for token in args.problem_ids.split(",") if token.strip()]
        except ValueError:
            print("--problem-ids must be comma-separated integers", file=sys.stderr)
            return 2

    try:
        overrides = load_overrides(args.overrides_json)
    except Exception as exc:
        print(f"failed to read overrides: {exc}", file=sys.stderr)
        return 2

    ensure_psycopg()
    conn = psycopg.connect(args.dsn)
    conn.autocommit = False

    stats = {
        "problems_targeted": 0,
        "profiles_upserted": 0,
    }

    try:
        with conn.cursor() as cur:
            profile_id_seq = resolve_id_sequence(
                cur,
                "problem_language_profiles",
                ("problem_language_profile_id_seq",),
            )
            profile_id_allocator: SequenceBlockAllocator | None = None
            if profile_id_seq:
                profile_id_allocator = SequenceBlockAllocator(
                    sequence_name=profile_id_seq,
                    block_size=resolve_sequence_increment(cur, profile_id_seq),
                )

            query, params = build_problem_query(problem_ids, args.limit)
            cur.execute(query, params)
            rows = cur.fetchall()
            if args.limit > 0 and not problem_ids:
                rows = sorted(rows, key=lambda r: r[0])

            stats["problems_targeted"] = len(rows)

            if args.truncate and not args.dry_run:
                cur.execute("TRUNCATE TABLE problem_language_profiles")
                if profile_id_seq:
                    # JPA sequence는 table-owned identity가 아니므로 명시적으로 초기화한다.
                    cur.execute("SELECT setval(%s::regclass, 1, false)", (profile_id_seq,))

            for problem_id, source_problem_id, title in rows:
                for language in languages:
                    starter = resolve_starter(
                        language=language,
                        title=title,
                        problem_id=int(problem_id),
                        source_problem_id=source_problem_id,
                        overrides=overrides,
                    )

                    if args.dry_run:
                        stats["profiles_upserted"] += 1
                        continue

                    upsert_profile(
                        cur,
                        id_allocator=profile_id_allocator,
                        problem_id=int(problem_id),
                        language_code=language,
                        starter_code=starter,
                        is_default=(language == args.default_language),
                    )
                    stats["profiles_upserted"] += 1

        if args.dry_run:
            conn.rollback()
        else:
            conn.commit()

    except Exception:
        conn.rollback()
        raise
    finally:
        conn.close()

    now = datetime.now().isoformat(timespec="seconds")
    print(f"[{now}] generation summary")
    for key, value in stats.items():
        print(f"- {key}: {value}")
    print(f"- dry_run: {args.dry_run}")
    print(f"- languages: {','.join(languages)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
