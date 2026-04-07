#!/usr/bin/env python3
"""
문제 원문(영문)을 보존한 채 한국어 번역본을 별도 테이블(problem_translations)에 적재한다.

기본 설계:
- source 테이블: problems (title/content/input_format/output_format)
- target 테이블: problem_translations (problem_id + language_code unique)
- source_hash 비교로 변경 없는 문제는 건너뛰어 재실행(idempotent) 가능
- AWS Translate 기반 번역 (EC2 IAM Role 환경 권장)
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
import time
from dataclasses import dataclass
from datetime import datetime
from typing import Any

psycopg = None
boto3 = None

# 제목 번역 예외/보정 사전 (필요 시 점진적으로 확장)
TITLE_OVERRIDES = {
    "GCD Sum": "최대공약수 합",
    "BCPC": "BCPC",
    "1-3-5": "1-3-5",
}


PROTECTED_SEGMENT_PATTERN = re.compile(
    r"```[\s\S]*?```|\$\$[\s\S]*?\$\$|(?<!\$)\$(?!\$)(?:\\.|[^$\\])+(?<!\\)\$(?!\$)",
    re.MULTILINE,
)


@dataclass
class ProblemRow:
    problem_id: int
    title: str
    content: str
    input_format: str | None
    output_format: str | None


def ensure_psycopg() -> None:
    global psycopg
    if psycopg is not None:
        return
    try:
        import psycopg as _psycopg
    except ImportError as exc:  # pragma: no cover
        raise SystemExit(
            "psycopg is required for DB write. Install with: pip install 'psycopg[binary]' boto3"
        ) from exc
    psycopg = _psycopg


def ensure_boto3() -> None:
    global boto3
    if boto3 is not None:
        return
    try:
        import boto3 as _boto3
    except ImportError as exc:  # pragma: no cover
        raise SystemExit("boto3 is required. Install with: pip install boto3") from exc
    boto3 = _boto3


def default_dsn() -> str:
    host = os.getenv("DB_HOST", os.getenv("RDS_HOST", "localhost"))
    port = os.getenv("DB_PORT", "5432")
    name = os.getenv("DB_NAME", "back")
    user = os.getenv("DB_USERNAME", "back")
    password = os.getenv("DB_PASSWORD")
    if not password:
        raise ValueError(
            "DB_PASSWORD is required. Export .env or pass --dsn/LOAD_DSN."
        )
    return f"postgresql://{user}:{password}@{host}:{port}/{name}"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Translate problems fields into Korean and upsert into problem_translations."
    )
    parser.add_argument("--dsn", default=os.getenv("LOAD_DSN"), help="PostgreSQL DSN")
    parser.add_argument("--offset", type=int, default=0)
    parser.add_argument("--limit", type=int, default=200)
    parser.add_argument("--chunk-size", type=int, default=50)
    parser.add_argument("--language-code", default="ko")
    parser.add_argument("--source-lang", default="en")
    parser.add_argument(
        "--aws-region", default=os.getenv("AWS_REGION", "ap-northeast-2")
    )
    parser.add_argument("--max-bytes-per-request", type=int, default=9000)
    parser.add_argument(
        "--request-interval-ms",
        type=int,
        default=120,
        help="Translate API call interval in milliseconds",
    )
    parser.add_argument(
        "--max-retries",
        type=int,
        default=3,
        help="Max retries per translate request",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Re-translate even when source hash is unchanged",
    )
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def ensure_schema(cur: Any) -> None:
    cur.execute(
        """
        CREATE TABLE IF NOT EXISTS problem_translations (
            id BIGSERIAL PRIMARY KEY,
            problem_id BIGINT NOT NULL REFERENCES problems(id) ON DELETE CASCADE,
            language_code VARCHAR(10) NOT NULL,
            title TEXT NOT NULL,
            content TEXT NOT NULL,
            input_format TEXT,
            output_format TEXT,
            source_hash VARCHAR(64) NOT NULL,
            provider VARCHAR(50) NOT NULL,
            translated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
            modified_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
            CONSTRAINT uq_problem_translations_problem_lang UNIQUE (problem_id, language_code)
        )
        """
    )
    cur.execute(
        """
        CREATE INDEX IF NOT EXISTS idx_problem_translations_lang_problem
        ON problem_translations (language_code, problem_id)
        """
    )


def fetch_problem_rows(cur: Any, offset: int, limit: int) -> list[ProblemRow]:
    cur.execute(
        """
        SELECT id, title, content, input_format, output_format
        FROM problems
        ORDER BY id
        OFFSET %s LIMIT %s
        """,
        (offset, limit),
    )
    rows: list[ProblemRow] = []
    for row in cur.fetchall():
        rows.append(
            ProblemRow(
                problem_id=int(row[0]),
                title=row[1] or "",
                content=row[2] or "",
                input_format=row[3],
                output_format=row[4],
            )
        )
    return rows


def fetch_existing_hashes(
    cur: Any, problem_ids: list[int], language_code: str
) -> dict[int, str]:
    if not problem_ids:
        return {}
    cur.execute(
        """
        SELECT problem_id, source_hash
        FROM problem_translations
        WHERE language_code = %s
          AND problem_id = ANY(%s)
        """,
        (language_code, problem_ids),
    )
    return {int(problem_id): source_hash for problem_id, source_hash in cur.fetchall()}


def source_hash_for(problem: ProblemRow) -> str:
    payload = {
        "title": problem.title or "",
        "content": problem.content or "",
        "input_format": problem.input_format or "",
        "output_format": problem.output_format or "",
    }
    serialized = json.dumps(payload, ensure_ascii=False, sort_keys=True)
    return hashlib.sha256(serialized.encode("utf-8")).hexdigest()


def split_text_by_bytes(text: str, max_bytes: int) -> list[str]:
    if not text:
        return [""]
    if len(text.encode("utf-8")) <= max_bytes:
        return [text]

    chunks: list[str] = []
    parts = re.split(r"(\n\n+)", text)
    current = ""
    for part in parts:
        if not part:
            continue

        candidate = current + part
        if len(candidate.encode("utf-8")) <= max_bytes:
            current = candidate
            continue

        if current:
            chunks.append(current)
            current = ""

        # 단일 part가 너무 긴 경우 강제로 문자 단위 분할
        if len(part.encode("utf-8")) > max_bytes:
            segment = ""
            for ch in part:
                test = segment + ch
                if len(test.encode("utf-8")) > max_bytes:
                    if segment:
                        chunks.append(segment)
                    segment = ch
                else:
                    segment = test
            if segment:
                current = segment
        else:
            current = part

    if current:
        chunks.append(current)

    return chunks


def translate_text_chunks(
    translator: "AwsTranslateClient", text: str, max_bytes_per_request: int
) -> str:
    if not text:
        return text
    pieces = split_text_by_bytes(text, max_bytes_per_request)
    translated = [translator.translate(piece) for piece in pieces]
    return "".join(translated)


def translate_with_protected_segments(
    translator: "AwsTranslateClient", text: str, max_bytes_per_request: int
) -> str:
    """
    수식/코드 구간은 원문 그대로 유지하고 일반 텍스트만 번역한다.
    placeholder 치환 방식보다 안전하게 수식 훼손을 방지한다.
    """
    result: list[str] = []
    cursor = 0

    for match in PROTECTED_SEGMENT_PATTERN.finditer(text):
        if match.start() > cursor:
            plain = text[cursor : match.start()]
            result.append(translate_text_chunks(translator, plain, max_bytes_per_request))
        result.append(match.group(0))
        cursor = match.end()

    if cursor < len(text):
        tail = text[cursor:]
        result.append(translate_text_chunks(translator, tail, max_bytes_per_request))

    return "".join(result)


def normalize_translated_text(text: str) -> str:
    if not text:
        return text

    # ".그는" / "?이것" 형태를 ". 그는" / "? 이것"으로 보정
    normalized = re.sub(r"([.!?])([가-힣A-Za-z])", r"\1 \2", text)
    # 닫는 괄호/따옴표 뒤 공백이 사라진 경우 보정
    normalized = re.sub(r"([)\]\"'])([가-힣A-Za-z])", r"\1 \2", normalized)
    # 과도한 공백 축소
    normalized = re.sub(r"[ \t]{2,}", " ", normalized)

    lines = [line.strip() for line in normalized.splitlines()]
    normalized = "\n".join(lines)
    normalized = re.sub(r"\n{3,}", "\n\n", normalized)
    return normalized.strip()


def normalize_translated_title(original_title: str, translated_title: str) -> str:
    original = (original_title or "").strip()
    translated = (translated_title or "").strip()
    if not translated:
        return original

    if original in TITLE_OVERRIDES:
        return TITLE_OVERRIDES[original]

    # 대회 약어/코드 형태는 원문 유지가 자연스럽다.
    if re.fullmatch(r"[A-Z0-9][A-Z0-9\- ]{1,20}", original):
        return original

    return normalize_translated_text(translated)


class AwsTranslateClient:
    def __init__(
        self,
        region: str,
        source_lang: str,
        target_lang: str,
        max_retries: int,
        request_interval_ms: int,
    ) -> None:
        ensure_boto3()
        self.client = boto3.client("translate", region_name=region)
        self.source_lang = source_lang
        self.target_lang = target_lang
        self.max_retries = max_retries
        self.request_interval_ms = request_interval_ms

    def translate(self, text: str) -> str:
        if not text:
            return text

        for attempt in range(1, self.max_retries + 1):
            try:
                response = self.client.translate_text(
                    Text=text,
                    SourceLanguageCode=self.source_lang,
                    TargetLanguageCode=self.target_lang,
                )
                result = response.get("TranslatedText")
                if isinstance(result, str) and result.strip():
                    if self.request_interval_ms > 0:
                        time.sleep(self.request_interval_ms / 1000.0)
                    return result
                raise RuntimeError("AWS Translate returned empty TranslatedText")
            except Exception:
                if attempt >= self.max_retries:
                    raise
                time.sleep(min(2.0, 0.4 * attempt))
        return text


def translate_field(
    translator: AwsTranslateClient,
    text: str | None,
    max_bytes_per_request: int,
    *,
    protect_segments: bool,
) -> str | None:
    if text is None:
        return None
    if not text.strip():
        return text

    if protect_segments:
        translated = translate_with_protected_segments(
            translator, text, max_bytes_per_request
        )
        return normalize_translated_text(translated)

    translated = translate_text_chunks(translator, text, max_bytes_per_request)
    return normalize_translated_text(translated)


def upsert_translation(
    cur: Any,
    problem_id: int,
    language_code: str,
    title: str,
    content: str,
    input_format: str | None,
    output_format: str | None,
    src_hash: str,
    provider: str,
) -> None:
    cur.execute(
        """
        INSERT INTO problem_translations (
            problem_id,
            language_code,
            title,
            content,
            input_format,
            output_format,
            source_hash,
            provider
        )
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (problem_id, language_code) DO UPDATE SET
            title = EXCLUDED.title,
            content = EXCLUDED.content,
            input_format = EXCLUDED.input_format,
            output_format = EXCLUDED.output_format,
            source_hash = EXCLUDED.source_hash,
            provider = EXCLUDED.provider,
            translated_at = NOW(),
            modified_at = NOW()
        """,
        (
            problem_id,
            language_code,
            title,
            content,
            input_format,
            output_format,
            src_hash,
            provider,
        ),
    )


def main() -> int:
    args = parse_args()
    if not args.dsn:
        try:
            args.dsn = default_dsn()
        except ValueError as exc:
            print(str(exc), file=sys.stderr)
            return 2

    stats = {
        "rows_fetched": 0,
        "translated": 0,
        "skipped_unchanged": 0,
        "failed": 0,
    }

    if args.dry_run:
        print("dry-run mode: no DB write")

    conn = None
    cur = None
    translator: AwsTranslateClient | None = None

    try:
        ensure_psycopg()
        conn = psycopg.connect(args.dsn)
        conn.autocommit = False
        cur = conn.cursor()
        ensure_schema(cur)
        conn.commit()

        translator = AwsTranslateClient(
            region=args.aws_region,
            source_lang=args.source_lang,
            target_lang=args.language_code,
            max_retries=args.max_retries,
            request_interval_ms=args.request_interval_ms,
        )

        remaining = args.limit
        next_offset = args.offset

        while remaining > 0:
            fetch_size = min(args.chunk_size, remaining)
            rows = fetch_problem_rows(cur, next_offset, fetch_size)
            if not rows:
                break

            stats["rows_fetched"] += len(rows)
            problem_ids = [row.problem_id for row in rows]
            existing_hashes = fetch_existing_hashes(cur, problem_ids, args.language_code)

            for row in rows:
                src_hash = source_hash_for(row)
                old_hash = existing_hashes.get(row.problem_id)
                if not args.force and old_hash == src_hash:
                    stats["skipped_unchanged"] += 1
                    continue

                try:
                    t_title = translate_field(
                        translator,
                        row.title,
                        args.max_bytes_per_request,
                        protect_segments=False,
                    )
                    t_title = normalize_translated_title(row.title, t_title or row.title)
                    t_content = translate_field(
                        translator,
                        row.content,
                        args.max_bytes_per_request,
                        protect_segments=True,
                    )
                    t_input = translate_field(
                        translator,
                        row.input_format,
                        args.max_bytes_per_request,
                        protect_segments=True,
                    )
                    t_output = translate_field(
                        translator,
                        row.output_format,
                        args.max_bytes_per_request,
                        protect_segments=True,
                    )

                    if args.dry_run:
                        stats["translated"] += 1
                        continue

                    upsert_translation(
                        cur,
                        problem_id=row.problem_id,
                        language_code=args.language_code,
                        title=t_title or row.title,
                        content=t_content or row.content,
                        input_format=t_input,
                        output_format=t_output,
                        src_hash=src_hash,
                        provider=f"aws-translate:{args.aws_region}:mask-v1",
                    )
                    stats["translated"] += 1
                except Exception as exc:
                    stats["failed"] += 1
                    print(
                        f"translate failed: problem_id={row.problem_id} reason={exc}",
                        file=sys.stderr,
                    )

            if not args.dry_run:
                conn.commit()

            next_offset += len(rows)
            remaining -= len(rows)

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
    print(f"[{now}] translation summary")
    for key, value in stats.items():
        print(f"- {key}: {value}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
