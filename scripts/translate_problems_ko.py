#!/usr/bin/env python3
"""
문제 원문(영문)을 보존한 채 한국어 번역본을 별도 테이블(problem_translations)에 적재한다.

기본 설계:
- source 테이블: problems (title/content/input_format/output_format)
- target 테이블: problem_translations (problem_id + language_code unique)
- source_hash 비교로 변경 없는 문제는 건너뛰어 재실행(idempotent) 가능

현재 번역 방식:
- OpenAI Responses API 사용
- 수식/코드 구간은 보호하고 일반 텍스트만 번역
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
OpenAI = None

TITLE_OVERRIDES = {
    "GCD Sum": "최대공약수 합",
    "BCPC": "BCPC",
    "1-3-5": "1-3-5",
}

# 긴 패턴부터 먼저 보호
PROTECTED_SEGMENT_PATTERN = re.compile(
    r"```[\s\S]*?```"                      # fenced code block
    r"|`[^`\n]+`"                         # inline code
    r"|\$\$\$[\s\S]*?\$\$\$"              # Codeforces-style triple-dollar math
    r"|\$\$[\s\S]*?\$\$"                  # block math
    r"|(?<!\$)\$(?!\$)(?:\\.|[^$\\])+(?<!\\)\$(?!\$)",  # inline $...$
    re.MULTILINE,
)
JSON_FENCE_PATTERN = re.compile(r"```(?:json)?\s*([\s\S]*?)```", re.IGNORECASE)


@dataclass
class ProblemRow:
    problem_id: int
    title: str
    content: str
    input_format: str | None
    output_format: str | None


@dataclass
class FieldPart:
    kind: str  # raw | plain
    text: str
    segment_id: str | None = None


@dataclass
class ProblemTranslationPlan:
    field_parts: dict[str, list[FieldPart] | None]
    plain_segments: list[tuple[str, str]]


def ensure_psycopg() -> None:
    global psycopg
    if psycopg is not None:
        return
    try:
        import psycopg as _psycopg
    except ImportError as exc:
        raise SystemExit(
            "psycopg is required for DB write. Install with: pip install 'psycopg[binary]' openai"
        ) from exc
    psycopg = _psycopg


def env_float(name: str, default: float) -> float:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        return float(raw)
    except ValueError:
        return default


def format_duration(seconds: float) -> str:
    total = max(0, int(round(seconds)))
    hours, rem = divmod(total, 3600)
    minutes, secs = divmod(rem, 60)
    if hours > 0:
        return f"{hours}h {minutes}m {secs}s"
    if minutes > 0:
        return f"{minutes}m {secs}s"
    return f"{secs}s"


def ensure_openai() -> None:
    global OpenAI
    if OpenAI is not None:
        return
    try:
        from openai import OpenAI as _OpenAI
    except ImportError as exc:
        raise SystemExit(
            "openai is required. Install with: pip install openai"
        ) from exc
    OpenAI = _OpenAI


def default_dsn() -> str:
    host = os.getenv("DB_HOST", os.getenv("RDS_HOST", "localhost"))
    port = os.getenv("DB_PORT", "5432")
    name = os.getenv("DB_NAME", "back")
    user = os.getenv("DB_USERNAME", "back")
    password = os.getenv("DB_PASSWORD")
    if not password:
        raise ValueError("DB_PASSWORD is required. Export .env or pass --dsn/LOAD_DSN.")
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
        "--model",
        default=os.getenv("OPENAI_TRANSLATE_MODEL", "gpt-4o-mini"),
        help="OpenAI model name",
    )
    parser.add_argument(
        "--max-bytes-per-request",
        type=int,
        default=4500,
        help="Translate request chunk size in UTF-8 bytes",
    )
    parser.add_argument(
        "--request-interval-ms",
        type=int,
        default=250,
        help="Translate request interval in milliseconds",
    )
    parser.add_argument(
        "--max-retries",
        type=int,
        default=3,
        help="Max retries per translate request",
    )
    parser.add_argument(
        "--price-input-per-1m",
        type=float,
        default=env_float("OPENAI_PRICE_INPUT_PER_1M", 0.0),
        help="Estimated USD price for 1M input tokens (optional)",
    )
    parser.add_argument(
        "--price-output-per-1m",
        type=float,
        default=env_float("OPENAI_PRICE_OUTPUT_PER_1M", 0.0),
        help="Estimated USD price for 1M output tokens (optional)",
    )
    parser.add_argument(
        "--estimated-output-ratio",
        type=float,
        default=env_float("TRANSLATE_EST_OUTPUT_RATIO", 1.0),
        help="Estimated output tokens = input tokens * ratio",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Re-translate even when source hash is unchanged",
    )
    parser.add_argument(
        "--continue-on-error",
        action="store_true",
        help="Continue after per-row failures (default: fail-fast)",
    )
    parser.add_argument(
        "--progress-every",
        type=int,
        default=1,
        help="Print row progress every N translated rows in each batch",
    )
    parser.add_argument("--dry-run", action="store_true")
    return parser.parse_args()


def ensure_schema(cur: Any) -> None:
    # 동시 실행 시 DDL 충돌을 피하기 위해 트랜잭션 단위 advisory lock으로 직렬화
    cur.execute(
        "SELECT pg_advisory_xact_lock(hashtext('problem_translations_schema_guard')::bigint)"
    )
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
    # 과거 스키마에 default가 빠져 있는 경우를 보정한다.
    cur.execute(
        """
        ALTER TABLE problem_translations
        ALTER COLUMN translated_at SET DEFAULT NOW(),
        ALTER COLUMN created_at SET DEFAULT NOW(),
        ALTER COLUMN modified_at SET DEFAULT NOW()
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


def build_field_parts(
        field_key: str,
        text: str | None,
        protect_segments: bool,
        max_bytes_per_request: int,
        next_segment_no: int,
) -> tuple[list[FieldPart] | None, list[tuple[str, str]], int]:
    if text is None:
        return None, [], next_segment_no

    parts: list[FieldPart] = []
    plain_segments: list[tuple[str, str]] = []
    piece_limit = max(512, max_bytes_per_request - 512)

    def append_plain(segment_text: str) -> None:
        nonlocal next_segment_no
        if segment_text == "":
            return
        pieces = split_text_by_bytes(segment_text, piece_limit)
        for piece in pieces:
            if piece.strip():
                segment_id = f"{field_key}:{next_segment_no:05d}"
                next_segment_no += 1
                parts.append(FieldPart(kind="plain", text=piece, segment_id=segment_id))
                plain_segments.append((segment_id, piece))
            else:
                parts.append(FieldPart(kind="raw", text=piece))

    if not protect_segments:
        append_plain(text)
        if not parts:
            parts.append(FieldPart(kind="raw", text=text))
        return parts, plain_segments, next_segment_no

    cursor = 0
    for match in PROTECTED_SEGMENT_PATTERN.finditer(text):
        if match.start() > cursor:
            append_plain(text[cursor:match.start()])
        parts.append(FieldPart(kind="raw", text=match.group(0)))
        cursor = match.end()

    if cursor < len(text):
        append_plain(text[cursor:])

    if not parts:
        parts.append(FieldPart(kind="raw", text=text))

    return parts, plain_segments, next_segment_no


def build_problem_translation_plan(
        problem: ProblemRow, max_bytes_per_request: int
) -> ProblemTranslationPlan:
    field_parts: dict[str, list[FieldPart] | None] = {}
    plain_segments: list[tuple[str, str]] = []
    segment_no = 0
    field_defs = (
        ("title", problem.title, False),
        ("content", problem.content, True),
        ("input_format", problem.input_format, True),
        ("output_format", problem.output_format, True),
    )
    for field_key, field_text, protect_segments in field_defs:
        parts, field_segments, segment_no = build_field_parts(
            field_key=field_key,
            text=field_text,
            protect_segments=protect_segments,
            max_bytes_per_request=max_bytes_per_request,
            next_segment_no=segment_no,
        )
        field_parts[field_key] = parts
        plain_segments.extend(field_segments)

    return ProblemTranslationPlan(field_parts=field_parts, plain_segments=plain_segments)


def batch_plain_segments(
        plain_segments: list[tuple[str, str]], max_bytes_per_request: int
) -> list[list[tuple[str, str]]]:
    if not plain_segments:
        return []

    limit = max(512, max_bytes_per_request)
    batches: list[list[tuple[str, str]]] = []
    current: list[tuple[str, str]] = []
    current_bytes = 32

    for segment_id, text in plain_segments:
        item_payload = json.dumps({"id": segment_id, "text": text}, ensure_ascii=False)
        item_bytes = len(item_payload.encode("utf-8")) + 1
        if current and current_bytes + item_bytes > limit:
            batches.append(current)
            current = []
            current_bytes = 32
        current.append((segment_id, text))
        current_bytes += item_bytes

    if current:
        batches.append(current)

    return batches


def estimate_problem_usage(
        plan: ProblemTranslationPlan, max_bytes_per_request: int
) -> tuple[int, int]:
    calls = len(batch_plain_segments(plan.plain_segments, max_bytes_per_request))
    input_chars = sum(len(text) for _, text in plan.plain_segments)
    return calls, input_chars


def estimate_tokens_from_chars(input_chars: int) -> int:
    if input_chars <= 0:
        return 0
    # 경험적 근사치: 영어/기호 텍스트 기준 1 token ~= 4 chars
    return max(1, (input_chars + 3) // 4)


def estimate_cost_usd(
        input_tokens: int,
        output_tokens: int,
        input_price_per_1m: float,
        output_price_per_1m: float,
) -> float:
    return (
            (input_tokens / 1_000_000.0) * max(0.0, input_price_per_1m)
            + (output_tokens / 1_000_000.0) * max(0.0, output_price_per_1m)
    )


def rebuild_field_from_parts(
        parts: list[FieldPart] | None, translated_by_id: dict[str, str]
) -> str | None:
    if parts is None:
        return None
    fragments: list[str] = []
    for part in parts:
        if part.kind == "plain":
            if part.segment_id is None:
                raise RuntimeError("plain part has no segment_id")
            translated = translated_by_id.get(part.segment_id)
            if translated is None:
                raise RuntimeError(f"missing translated segment: {part.segment_id}")
            fragments.append(translated)
        else:
            fragments.append(part.text)
    return "".join(fragments)


def normalize_translated_text(text: str) -> str:
    if not text:
        return text

    normalized = text
    normalized = re.sub(r"([.!?])([가-힣A-Za-z])", r"\1 \2", normalized)
    normalized = re.sub(r"([)\]\"'])([가-힣A-Za-z])", r"\1 \2", normalized)
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

    if re.fullmatch(r"[A-Z0-9][A-Z0-9 -]{1,20}", original):
        return original

    return normalize_translated_text(translated)

class OpenAITranslateClient:
    def __init__(
            self,
            source_lang: str,
            target_lang: str,
            model: str,
            max_retries: int,
            request_interval_ms: int,
    ) -> None:
        ensure_openai()
        self.client = OpenAI()
        self.source_lang = source_lang
        self.target_lang = target_lang
        self.model = model
        self.max_retries = max_retries
        self.request_interval_ms = request_interval_ms

    def _translate_text_single(self, text: str) -> str:
        if not text:
            return text

        system_prompt = (
            f"You are a professional translator. "
            f"Translate the user's text from {self.source_lang} to {self.target_lang}. "
            f"Preserve meaning faithfully. "
            f"Do not add explanations, notes, markdown fences, or commentary. "
            f"Return only the translated text."
        )
        for attempt in range(1, self.max_retries + 1):
            try:
                response = self.client.responses.create(
                    model=self.model,
                    input=[
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": text},
                    ],
                )
                result = response.output_text or ""
                if result.strip():
                    if self.request_interval_ms > 0:
                        time.sleep(self.request_interval_ms / 1000.0)
                    return result
                raise RuntimeError("empty translation from single-text fallback")
            except Exception:
                if attempt >= self.max_retries:
                    raise
                time.sleep(min(2.0, 0.5 * attempt))
        return text

    @staticmethod
    def _parse_json_response(raw: str) -> Any:
        candidates: list[str] = []
        stripped = (raw or "").strip()
        if stripped:
            candidates.append(stripped)

        fence_match = JSON_FENCE_PATTERN.search(raw or "")
        if fence_match:
            candidates.insert(0, fence_match.group(1).strip())

        first_obj = (raw or "").find("{")
        last_obj = (raw or "").rfind("}")
        if 0 <= first_obj < last_obj:
            candidates.append((raw or "")[first_obj:last_obj + 1].strip())

        for cand in candidates:
            if not cand:
                continue
            try:
                return json.loads(cand)
            except json.JSONDecodeError:
                continue

        raise RuntimeError("OpenAI returned non-JSON response for translation batch")

    def translate_segments_batch(
            self, segments: list[tuple[str, str]]
    ) -> dict[str, str]:
        if not segments:
            return {}

        system_prompt = (
            "You are a professional translator. "
            f"Translate text from {self.source_lang} to {self.target_lang}. "
            "Input is JSON with `segments` array. "
            "Return ONLY JSON object: "
            '{"translations":[{"id":"...","text":"..."}]}. '
            "Do not omit entries. Keep id values unchanged."
        )
        payload = {
            "segments": [{"id": segment_id, "text": text} for segment_id, text in segments]
        }
        expected_ids = [segment_id for segment_id, _ in segments]
        source_by_id = {segment_id: text for segment_id, text in segments}

        for attempt in range(1, self.max_retries + 1):
            try:
                response = self.client.responses.create(
                    model=self.model,
                    input=[
                        {"role": "system", "content": system_prompt},
                        {
                            "role": "user",
                            "content": json.dumps(payload, ensure_ascii=False),
                        },
                    ],
                )
                result = response.output_text or ""
                parsed = self._parse_json_response(result)
                if isinstance(parsed, dict):
                    entries = parsed.get("translations")
                elif isinstance(parsed, list):
                    entries = parsed
                else:
                    raise RuntimeError("translation JSON shape is invalid")

                if not isinstance(entries, list):
                    raise RuntimeError("translation JSON has no `translations` array")

                translated_by_id: dict[str, str] = {}
                for entry in entries:
                    if not isinstance(entry, dict):
                        continue
                    segment_id = entry.get("id")
                    translated_text = entry.get("text")
                    if isinstance(segment_id, str) and isinstance(translated_text, str):
                        translated_by_id[segment_id] = translated_text

                missing = [sid for sid in expected_ids if sid not in translated_by_id]
                if missing:
                    if attempt < self.max_retries:
                        missing_preview = ", ".join(missing[:3])
                        raise RuntimeError(
                            f"missing translated segments ({len(missing)}): {missing_preview}"
                        )
                    # 마지막 시도에서도 누락되면 누락 항목만 개별 번역으로 보정
                    for sid in missing:
                        translated_by_id[sid] = self._translate_text_single(
                            source_by_id[sid]
                        )

                if self.request_interval_ms > 0:
                    time.sleep(self.request_interval_ms / 1000.0)

                return {sid: translated_by_id[sid] for sid in expected_ids}
            except Exception:
                if attempt >= self.max_retries:
                    raise
                time.sleep(min(2.0, 0.5 * attempt))

        return {}


def translate_problem_with_plan(
        translator: OpenAITranslateClient,
        problem: ProblemRow,
        plan: ProblemTranslationPlan,
        max_bytes_per_request: int,
) -> tuple[str, str, str | None, str | None]:
    translated_by_id: dict[str, str] = {}
    batches = batch_plain_segments(plan.plain_segments, max_bytes_per_request)
    for batch in batches:
        translated_by_id.update(translator.translate_segments_batch(batch))

    raw_title = rebuild_field_from_parts(plan.field_parts["title"], translated_by_id)
    raw_content = rebuild_field_from_parts(plan.field_parts["content"], translated_by_id)
    raw_input = rebuild_field_from_parts(plan.field_parts["input_format"], translated_by_id)
    raw_output = rebuild_field_from_parts(plan.field_parts["output_format"], translated_by_id)

    t_title = normalize_translated_title(problem.title, raw_title or problem.title)
    t_content = normalize_translated_text(raw_content or problem.content)
    t_input = None if raw_input is None else normalize_translated_text(raw_input)
    t_output = None if raw_output is None else normalize_translated_text(raw_output)
    return t_title, t_content, t_input, t_output


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
            provider,
            translated_at
        )
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, NOW())
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
        "estimated_api_calls": 0,
        "estimated_input_tokens": 0,
        "estimated_output_tokens": 0,
        "estimated_cost_usd": 0.0,
    }

    if args.dry_run:
        print("dry-run mode: no API call, no DB write")
    else:
        mode = "continue-on-error" if args.continue_on_error else "fail-fast"
        print(f"run mode: {mode}")

    conn = None
    cur = None
    translator: OpenAITranslateClient | None = None
    progress_every = max(1, args.progress_every)
    run_started_at = time.monotonic()

    try:
        ensure_psycopg()
        conn = psycopg.connect(args.dsn)
        conn.autocommit = False
        cur = conn.cursor()
        ensure_schema(cur)
        conn.commit()

        if not args.dry_run:
            translator = OpenAITranslateClient(
                source_lang=args.source_lang,
                target_lang=args.language_code,
                model=args.model,
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

            candidates: list[
                tuple[ProblemRow, str, ProblemTranslationPlan, int, int, int, int, float]
            ] = []
            for row in rows:
                src_hash = source_hash_for(row)
                old_hash = existing_hashes.get(row.problem_id)
                if not args.force and old_hash == src_hash:
                    stats["skipped_unchanged"] += 1
                    continue

                plan = build_problem_translation_plan(row, args.max_bytes_per_request)
                estimated_calls, estimated_input_chars = estimate_problem_usage(
                    plan, args.max_bytes_per_request
                )
                estimated_input_tokens = estimate_tokens_from_chars(estimated_input_chars)
                estimated_output_tokens = int(
                    estimated_input_tokens * max(0.0, args.estimated_output_ratio)
                )
                estimated_cost = estimate_cost_usd(
                    estimated_input_tokens,
                    estimated_output_tokens,
                    args.price_input_per_1m,
                    args.price_output_per_1m,
                )
                candidates.append(
                    (
                        row,
                        src_hash,
                        plan,
                        estimated_calls,
                        estimated_input_tokens,
                        estimated_output_tokens,
                        estimated_input_chars,
                        estimated_cost,
                    )
                )

            if candidates:
                batch_calls = sum(item[3] for item in candidates)
                batch_input_tokens = sum(item[4] for item in candidates)
                batch_output_tokens = sum(item[5] for item in candidates)
                batch_input_chars = sum(item[6] for item in candidates)
                batch_cost = sum(item[7] for item in candidates)
                stats["estimated_api_calls"] += batch_calls
                stats["estimated_input_tokens"] += batch_input_tokens
                stats["estimated_output_tokens"] += batch_output_tokens
                stats["estimated_cost_usd"] += batch_cost

                if args.price_input_per_1m > 0 or args.price_output_per_1m > 0:
                    print(
                        f"[estimate] offset={next_offset} rows={len(candidates)} "
                        f"calls~{batch_calls} in_chars~{batch_input_chars} "
                        f"in_tok~{batch_input_tokens} out_tok~{batch_output_tokens} "
                        f"cost~${batch_cost:.6f}"
                    )
                else:
                    print(
                        f"[estimate] offset={next_offset} rows={len(candidates)} "
                        f"calls~{batch_calls} in_chars~{batch_input_chars} "
                        f"in_tok~{batch_input_tokens} out_tok~{batch_output_tokens} "
                        f"cost~N/A (set --price-input-per-1m/--price-output-per-1m)"
                    )
                if not args.dry_run:
                    print(
                        f"[progress] batch_start offset={next_offset} "
                        f"rows={len(candidates)} est_calls={batch_calls}"
                    )

            batch_started_at = time.monotonic()
            batch_total_rows = len(candidates)
            batch_total_calls = sum(item[3] for item in candidates)
            batch_done_rows = 0
            batch_done_calls = 0

            for row, src_hash, plan, row_est_calls, _, _, _, _ in candidates:
                if args.dry_run:
                    stats["translated"] += 1
                    continue

                row_started_at = time.monotonic()
                try:
                    if args.continue_on_error:
                        cur.execute("SAVEPOINT sp_translate_row")
                    if translator is None:
                        raise RuntimeError("translator is not initialized")
                    t_title, t_content, t_input, t_output = translate_problem_with_plan(
                        translator=translator,
                        problem=row,
                        plan=plan,
                        max_bytes_per_request=args.max_bytes_per_request,
                    )

                    upsert_translation(
                        cur,
                        problem_id=row.problem_id,
                        language_code=args.language_code,
                        title=t_title or row.title,
                        content=t_content or row.content,
                        input_format=t_input,
                        output_format=t_output,
                        src_hash=src_hash,
                        provider=f"openai:{args.model}:v1",
                    )
                    if args.continue_on_error:
                        cur.execute("RELEASE SAVEPOINT sp_translate_row")
                    stats["translated"] += 1
                    row_status = "ok"
                except Exception as exc:
                    if args.continue_on_error:
                        # 현재 행만 되돌리고 다음 행으로 진행
                        cur.execute("ROLLBACK TO SAVEPOINT sp_translate_row")
                        cur.execute("RELEASE SAVEPOINT sp_translate_row")
                    stats["failed"] += 1
                    row_status = "failed"
                    print(
                        f"translate failed: problem_id={row.problem_id} reason={exc}",
                        file=sys.stderr,
                    )
                    batch_done_rows += 1
                    batch_done_calls += row_est_calls
                    if (
                            batch_done_rows % progress_every == 0
                            or batch_done_rows == batch_total_rows
                    ):
                        batch_elapsed = time.monotonic() - batch_started_at
                        total_elapsed = time.monotonic() - run_started_at
                        avg_sec_per_call = (
                            batch_elapsed / batch_done_calls
                            if batch_done_calls > 0
                            else 0.0
                        )
                        rem_calls = max(0, batch_total_calls - batch_done_calls)
                        batch_eta = avg_sec_per_call * rem_calls
                        row_elapsed = time.monotonic() - row_started_at
                        print(
                            f"[progress] row {batch_done_rows}/{batch_total_rows} "
                            f"problem_id={row.problem_id} status={row_status} "
                            f"row_time={format_duration(row_elapsed)} "
                            f"batch_elapsed={format_duration(batch_elapsed)} "
                            f"batch_eta~{format_duration(batch_eta)} "
                            f"total_elapsed={format_duration(total_elapsed)}"
                        )
                    if not args.continue_on_error:
                        raise
                    continue

                batch_done_rows += 1
                batch_done_calls += row_est_calls
                if (
                        batch_done_rows % progress_every == 0
                        or batch_done_rows == batch_total_rows
                ):
                    batch_elapsed = time.monotonic() - batch_started_at
                    total_elapsed = time.monotonic() - run_started_at
                    avg_sec_per_call = (
                        batch_elapsed / batch_done_calls if batch_done_calls > 0 else 0.0
                    )
                    rem_calls = max(0, batch_total_calls - batch_done_calls)
                    batch_eta = avg_sec_per_call * rem_calls
                    row_elapsed = time.monotonic() - row_started_at
                    print(
                        f"[progress] row {batch_done_rows}/{batch_total_rows} "
                        f"problem_id={row.problem_id} status={row_status} "
                        f"row_time={format_duration(row_elapsed)} "
                        f"batch_elapsed={format_duration(batch_elapsed)} "
                        f"batch_eta~{format_duration(batch_eta)} "
                        f"total_elapsed={format_duration(total_elapsed)}"
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
        if isinstance(value, float):
            print(f"- {key}: {value:.6f}")
        else:
            print(f"- {key}: {value}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
