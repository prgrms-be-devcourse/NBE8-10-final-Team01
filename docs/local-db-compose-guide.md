# 로컬 DB/데이터 적재 가이드

로컬 개발/데이터 적재를 팀 공통으로 맞추기 위한 최소 실행 가이드입니다.
아래 명령은 현재 위치와 관계없이 프로젝트 루트를 자동으로 찾아 실행하도록 작성했습니다.

## 0) `.env` 준비 (필수)

민감한 값은 저장소에 커밋하지 않고 `.env`에서 관리합니다.

```bash
cd "$(git rev-parse --show-toplevel)" && cp .env.example .env
```

- `.env`의 `DB_PASSWORD`는 로컬 전용 강한 값으로 변경하세요.
- `.env`는 `.gitignore`에 포함되어 있어 커밋되지 않습니다.

## 1) PostgreSQL 실행 (Docker Compose)

주의: 실행 전에 docker desktop이 켜져 있어야 합니다.
로컬 컨테이너 이미지는 `pgvector/pgvector:pg16`을 사용합니다.

```bash
cd "$(git rev-parse --show-toplevel)" && docker compose up -d postgres
cd "$(git rev-parse --show-toplevel)" && docker compose ps
```

- 기본 포트: `5432`
- 기본 DB: `back`
- 기본 계정: `DB_USERNAME / DB_PASSWORD`
- 실제 값은 `.env`를 따릅니다.
- DB 최초 초기화 시 `vector` extension이 자동 생성됩니다.

## 2) Spring Boot 실행 (스키마 반영)

- IntelliJ에서 `BackApplication`을 실행한다. (권장)
- 대체 방법(터미널):

```bash
cd "$(git rev-parse --show-toplevel)" && set -a && source .env && set +a && ./gradlew bootRun
```

- `application-dev.yml`은 `DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD`를 사용합니다.
- 앱 1회 기동 후 `problems`, `test_cases`, `tags`, `problem_tag_connect` 테이블이 생성됩니다.

## 3) open-r1/codeforces 데이터 적재

원칙: 원본 데이터 파일(parquet/json/csv 등)은 저장소에 커밋하지 않고 로컬에서만 사용합니다.

```bash
cd "$(git rev-parse --show-toplevel)" && python3 -m pip install -U requests "psycopg[binary]"
cd "$(git rev-parse --show-toplevel)" && set -a && source .env && set +a && python3 scripts/load_openr1_verifiable.py --limit 200 --chunk-size 50
```

- `--limit`은 "이번 실행에서 가져올 최대 문제 수"입니다.
- 위 예시는 동작 확인용 샘플 적재(200건)입니다.
- `--chunk-size`는 HF API 요청 1회당 조회 건수입니다.
- 현재 로더는 HF API에서 바로 읽어 DB에 넣으며, 데이터 파일을 저장소에 저장하지 않습니다.
- `input_format`, `output_format`은 `problems` 컬럼에 분리 저장됩니다.
- 중복 판별은 `source_problem_id`(예: `852/A`)를 우선 사용합니다.
- 같은 구간을 다시 실행하면 기존 문제는 건너뜁니다. (`skipped_existing_problem` 증가)
- `difficulty`는 `EASY`, `MEDIUM`, `HARD` 3단계 enum 문자열로 저장됩니다.

실행 후 요약 로그는 아래 형태로 출력됩니다. (숫자는 실행마다 달라집니다)

```text
[2026-03-30T10:42:15] load summary
- rows_fetched: 200
- rows_loaded: 173
- problems_inserted: 160
- skipped_existing_problem: 13
- sample_tests: 346
- hidden_tests: 1180
```

빠르게 동작 확인만 할 때는 10건만 먼저 적재해도 됩니다.

```bash
cd "$(git rev-parse --show-toplevel)" && set -a && source .env && set +a && python3 scripts/load_openr1_verifiable.py --truncate --limit 10 --chunk-size 10
```

전체 적재에 가깝게 넣고 싶으면 `limit`을 충분히 크게 줍니다.

```bash
cd "$(git rev-parse --show-toplevel)" && set -a && source .env && set +a && python3 scripts/load_openr1_verifiable.py --truncate --limit 10000 --chunk-size 100
```

- 실제 데이터가 `limit`보다 적으면 가능한 행까지만 적재하고 종료합니다.
- 기본 DSN도 동일한 `DB_*` 환경변수를 사용합니다.
- `--truncate`는 `problems`를 참조하는 연관 테이블까지 `CASCADE`로 비웁니다(개발 DB에서만 사용).
- 샘플 데이터를 초기화 후 다시 넣을 때:

```bash
cd "$(git rev-parse --show-toplevel)" && set -a && source .env && set +a && python3 scripts/load_openr1_verifiable.py --truncate --limit 200 --chunk-size 50
```

## 4) 언어 starter 생성 (선택)

문제 상세 응답에서 `supportedLanguages/defaultLanguage/starterCodes`를 쓰려면 실행합니다.

```bash
cd "$(git rev-parse --show-toplevel)" && set -a && source .env && set +a && python3 scripts/generate_problem_language_profiles.py --limit 200
```

- 기본 생성 언어: `python3, java, c, cpp17, javascript`
- 기본 선택 언어: `python3`
- `(problem_id, language_code)` 기준 upsert

실행 후 요약 로그 예시:

```text
[2026-03-30T10:45:02] generation summary
- problems_targeted: 200
- profiles_upserted: 1000
- dry_run: False
- languages: python3,java,c,cpp17,javascript
```

`load`를 `--truncate`로 다시 돌렸으면 아래도 함께 실행하세요.

```bash
cd "$(git rev-parse --show-toplevel)" && set -a && source .env && set +a && python3 scripts/generate_problem_language_profiles.py --truncate --limit 200
```

문제별 커스텀 시그니처가 필요하면 override 파일을 사용합니다.

```bash
cd "$(git rev-parse --show-toplevel)" && set -a && source .env && set +a && python3 scripts/generate_problem_language_profiles.py --overrides-json scripts/starter_overrides.example.json
```

override JSON 예시는 아래 구조를 따릅니다.

```json
{
  "problem_id": {
    "9951": {
      "java": "class Solution {\\n    public int solve(int[] nums) {\\n        return 0;\\n    }\\n}\\n"
    }
  },
  "source_problem_id": {
    "852/A": {
      "java": "class Solution {\\n    public int[] twoSum(int[] nums, int target) {\\n        return new int[]{};\\n    }\\n}\\n"
    }
  }
}
```

- `problem_id`: DB의 `problems.id` 기준으로 starter override
- `source_problem_id`: 원본 문제 키(예: `852/A`) 기준으로 starter override
- 두 조건이 동시에 있으면 `problem_id` override가 우선됩니다.

특정 문제만 재생성하고 싶으면:

```bash
cd "$(git rev-parse --show-toplevel)" && set -a && source .env && set +a && python3 scripts/generate_problem_language_profiles.py --problem-ids 1,2,3 --languages python3,java,c,cpp17,javascript --default-language python3
```

## 5) IntelliJ/DBeaver에서 확인

### IntelliJ 접속 설정

아래 값으로 Data Source를 생성합니다.
- Host: `localhost`
- Port: `5432`
- User: `back`
- Password: `.env`의 `DB_PASSWORD` 값
- Database: `back`

![IntelliJ PostgreSQL 데이터소스 설정 화면](./assets/intellij-postgresql-datasource.png)

1. `Database` 탭에서 PostgreSQL 데이터소스 추가  
   host=`localhost`, port=`5432`, db=`back`, user=`back`, password=`.env의 DB_PASSWORD`
2. 아래 쿼리로 적재/생성 결과 확인

```sql
select count(*) as problems from problems;
select count(*) as tags from tags;
select count(*) as problem_tag_connect from problem_tag_connect;
select count(*) as test_cases from test_cases;
select count(*) as language_profiles from problem_language_profiles;

select id, source_problem_id, title, difficulty, difficulty_rating, input_mode, judge_type
from problems
order by id desc
limit 20;

select p.id, p.title, lp.language_code, lp.is_default
from problems p
join problem_language_profiles lp on lp.problem_id = p.id
order by p.id desc, lp.is_default desc, lp.language_code asc
limit 30;
```

빠른 확인 기준:
- `language_profiles`가 `problems * 5`에 가깝게 나오면 기본 5개 언어 starter가 정상 생성된 상태입니다.
- `is_default = true` 행은 문제당 1개여야 합니다.

### DBeaver 접속 설정

1. `Database > New Database Connection > PostgreSQL`
2. Host=`localhost`, Port=`5432`, Database=`back`, User=`back`, Password=`.env의 DB_PASSWORD`
3. `Test Connection` 성공 후 `Finish`
4. 좌측 `Database Navigator`에서 `Schemas > public > Tables` 확인

## 6) 자주 나는 오류

- `SyntaxError: future feature annotations is not defined`
  - 원인: Python 3.6 이하로 스크립트를 실행한 경우
  - 조치: `python3 --version` 확인 후 3.9+로 실행
  - 예시:
    ```bash
    pyenv local 3.10.13
    exec "$SHELL" -l
    python3 --version
    ```

- `FATAL: password authentication failed for user "${DB_USERNAME}"`
  - 원인: `.env`가 export되지 않아 플레이스홀더 문자열이 그대로 들어간 경우
  - 조치: `set -a && source .env && set +a` 후 재실행

- `relation "problems" does not exist`
  - 원인: 스키마 생성 전에 로더를 먼저 실행한 경우
  - 조치: `BackApplication` 1회 실행 후 다시 로더 실행

- `null value in column "id" ... violates not-null constraint`
  - 원인: 스키마 생성이 덜 되었거나 로더 실행 시점에 시퀀스를 찾지 못한 경우
  - 조치: `BackApplication`을 먼저 1회 실행 후 로더를 다시 실행

- `type "vector" does not exist`
  - 원인: 기존 볼륨(이전 postgres 이미지)으로 실행 중이어서 pgvector extension이 아직 없는 경우
  - 조치: 아래 중 하나를 수행
    - `docker compose exec postgres psql -U "$DB_USERNAME" -d "$DB_NAME" -c "CREATE EXTENSION IF NOT EXISTS vector;"`
    - 또는 개발용이면 `docker compose down -v` 후 다시 `docker compose up -d postgres`로 볼륨을 재초기화

- 현재 폴더가 `docs`이면 아래처럼 실행해도 됩니다.

```bash
set -a && source ../.env && set +a && python3 ../scripts/load_openr1_verifiable.py --limit 200 --chunk-size 50
```

## 7) 마무리 체크

- 문서의 모든 명령어 블록이 닫혀 있는지 확인합니다.
- `docker compose ps`에서 `postgres`가 `healthy` 상태인지 확인합니다.
