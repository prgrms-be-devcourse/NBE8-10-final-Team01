# 로컬 DB/데이터 적재 가이드 (6단계)

아래 6단계만 실행하면 됩니다.

## 1) PostgreSQL + Redis 실행/확인

```bash
cd "$(git rev-parse --show-toplevel)" && docker compose up -d postgres redis
cd "$(git rev-parse --show-toplevel)" && docker compose ps postgres redis
```

주의:
- Redis가 떠 있지 않으면 Spring 부팅 시 `Unable to connect to Redis server: localhost:6379`로 실패할 수 있습니다.

## 2) Spring Boot 실행 (스키마 생성)

- IntelliJ에서 `BackApplication`을 1회 실행

## 3) 문제 데이터 적재

```bash
cd "$(git rev-parse --show-toplevel)" && python3 -m pip install -U requests "psycopg[binary]"
cd "$(git rev-parse --show-toplevel)" && set -a && source .env && set +a && python3 scripts/load_openr1_verifiable.py --limit 200 --chunk-size 50
```

## 4) starter code 기본 생성

```bash
cd "$(git rev-parse --show-toplevel)" && set -a && source .env && set +a && python3 scripts/generate_problem_language_profiles.py --limit 200
```

## 5) starter code override 적용 (선택)

```bash
cd "$(git rev-parse --show-toplevel)" && set -a && source .env && set +a && python3 scripts/generate_problem_language_profiles.py --overrides-json scripts/starter_overrides.example.json
```

의미(아주 간단히):
- 4번은 전체 문제 starter를 기본 템플릿으로 생성
- 5번은 `overrides-json`에 적어둔 문제만 덮어씀

예시(`scripts/starter_overrides.example.json` 기준):
- `source_problem_id = "852/A"`의 `java` starter만 지정한 코드로 교체
- 그 외 언어(`python3` 등)는 4번에서 만든 기본 starter 유지

## 6) 문제 본문 한국어 번역본 적재 (선택)

원문(`problems`)은 유지하고, 번역본은 `problem_translations` 테이블에 upsert됩니다.

```bash
cd "$(git rev-parse --show-toplevel)" && python3 -m pip install -U "psycopg[binary]" openai
cd "$(git rev-parse --show-toplevel)" && set -a && source .env && set +a && python3 scripts/translate_problems_ko.py --dry-run --limit 50
cd "$(git rev-parse --show-toplevel)" && set -a && source .env && set +a && python3 scripts/translate_problems_ko.py --force --limit 200 --chunk-size 50 --request-interval-ms 250
```
주의:
- 운영 환경에서는 EC2 IAM Role에 `TranslateText` 권한이 필요합니다.
- `--force` 없이 재실행하면 source hash가 동일한 문제는 건너뜁니다.
