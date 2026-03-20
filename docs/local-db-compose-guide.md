# 로컬 DB/데이터 적재 가이드

로컬 개발/데이터 적재를 팀 공통으로 맞추기 위한 최소 실행 가이드입니다.
아래 명령은 현재 위치와 관계없이 프로젝트 루트를 자동으로 찾아 실행하도록 작성했습니다.

## 1) PostgreSQL 실행 (Docker Compose)

주의: 실행 전에 docker desktop이 켜져 있어야 합니다.

```bash
cd "$(git rev-parse --show-toplevel)" && docker compose up -d postgres
cd "$(git rev-parse --show-toplevel)" && docker compose ps
```

- 기본 포트: `5432`
- 기본 DB: `back`
- 기본 계정: `back / back1234`
- 실제 값은 `.env`를 따릅니다.

## 2) Spring Boot 실행 (스키마 반영)

- IntelliJ에서 `BackApplication`을 실행한다. (권장)
- 대체 방법(터미널):

```bash
cd "$(git rev-parse --show-toplevel)" && ./gradlew bootRun
```

- `application-dev.yml`은 `DB_HOST/DB_PORT/DB_NAME/DB_USERNAME/DB_PASSWORD`를 사용합니다.
- 앱 1회 기동 후 `problems`, `test_cases`, `tags`, `problem_tag_connect` 테이블이 생성됩니다.

## 3) open-r1/codeforces 데이터 적재

```bash
cd "$(git rev-parse --show-toplevel)" && python3 -m pip install -U requests "psycopg[binary]"
cd "$(git rev-parse --show-toplevel)" && python3 scripts/load_openr1_verifiable.py --limit 200 --chunk-size 50
```

- `--limit`은 "이번 실행에서 가져올 최대 문제 수"입니다.
- 위 예시는 동작 확인용 샘플 적재(200건)입니다.
- `--chunk-size`는 HF API 요청 1회당 조회 건수입니다.
- `input_format`, `output_format`은 `problems` 컬럼에 분리 저장됩니다.
- 중복 판별은 `source_problem_id`(예: `852/A`)를 우선 사용합니다.
- 같은 구간을 다시 실행하면 기존 문제는 건너뜁니다. (`skipped_existing_problem` 증가)
- `difficulty`는 `EASY`, `MEDIUM`, `HARD` 3단계 enum 문자열로 저장됩니다.

전체 적재에 가깝게 넣고 싶으면 `limit`을 충분히 크게 줍니다.

```bash
cd "$(git rev-parse --show-toplevel)" && python3 scripts/load_openr1_verifiable.py --truncate --limit 10000 --chunk-size 100
```

- 실제 데이터가 `limit`보다 적으면 가능한 행까지만 적재하고 종료합니다.
- 기본 DSN도 동일한 `DB_*` 환경변수를 사용합니다.
- `--truncate`는 `problems`를 참조하는 연관 테이블까지 `CASCADE`로 비웁니다(개발 DB에서만 사용).
- 샘플 데이터를 초기화 후 다시 넣을 때:

```bash
cd "$(git rev-parse --show-toplevel)" && python3 scripts/load_openr1_verifiable.py --truncate --limit 200 --chunk-size 50
```

## 4) IntelliJ에서 확인

### IntelliJ 접속 설정

아래 값으로 Data Source를 생성합니다.
- Host: `localhost`
- Port: `5432`
- User: `back`
- Password: `back1234`
- Database: `back`

![IntelliJ PostgreSQL 데이터소스 설정 화면](./assets/intellij-postgresql-datasource.png)

1. `Database` 탭에서 PostgreSQL 데이터소스 추가  
   host=`localhost`, port=`5432`, db=`back`, user=`back`, password=`back1234`
2. 아래 쿼리로 적재 결과 확인

```sql
select count(*) as problems from problems;
select count(*) as tags from tags;
select count(*) as problem_tag_connect from problem_tag_connect;
select count(*) as test_cases from test_cases;

select id, source_problem_id, title, difficulty, difficulty_rating, input_mode, judge_type
from problems
order by id desc
limit 20;

select difficulty, count(*) from problems group by difficulty order by difficulty;

select id, left(input_format, 80), left(output_format, 80)
from problems
order by id desc
limit 20;
```

### DBeaver 접속 설정

1. `Database > New Database Connection > PostgreSQL`
2. Host=`localhost`, Port=`5432`, Database=`back`, User=`back`, Password=`back1234`
3. `Test Connection` 성공 후 `Finish`
4. 좌측 `Database Navigator`에서 `Schemas > public > Tables` 확인

## 5) 자주 나는 오류

- `relation "problems" does not exist`
  - 원인: 스키마 생성 전에 로더를 먼저 실행한 경우
  - 조치: `BackApplication` 1회 실행 후 다시 로더 실행

- `null value in column "id" ... violates not-null constraint`
  - 원인: 스키마 생성이 덜 되었거나 로더 실행 시점에 시퀀스를 찾지 못한 경우
  - 조치: `BackApplication`을 먼저 1회 실행 후 로더를 다시 실행

- 현재 폴더가 `docs`이면 아래처럼 실행해도 됩니다.

```bash
python3 ../scripts/load_openr1_verifiable.py --limit 200 --chunk-size 50
```
