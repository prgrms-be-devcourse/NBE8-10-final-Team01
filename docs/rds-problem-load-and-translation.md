# 운영 RDS 문제 적재 + 한국어 번역 배치 가이드

## 1) 어디서 실행하나

- 권장: **SSM Session Manager**로 운영 EC2에 접속해서 실행
- 이유: RDS는 보통 VPC 내부 접근만 허용되므로 로컬에서 직접 접근이 막혀있음

## 2) 사전 확인

1. RDS 스냅샷 1회 생성
2. EC2 IAM Role에 `translate:TranslateText` 권한 부여 (번역 배치 시)
3. `/etc/environment` 또는 `.env`에 DB 접속 변수 확인

필수 환경 변수:

- `DB_HOST` (또는 `RDS_HOST`)
- `DB_PORT` (기본 5432)
- `DB_NAME`
- `DB_USERNAME`
- `DB_PASSWORD`
- `AWS_REGION` (기본값 `ap-northeast-2`)

## 3) 문제 데이터 적재 (소량 검증 → 본 적재)

```bash
cd /home/ubuntu/NBE8-10-final-Team01
python3 -m pip install -U requests "psycopg[binary]"
set -a && source /etc/environment && set +a

# dry-run
python3 scripts/load_openr1_verifiable.py --dry-run --limit 100 --chunk-size 50

# 소량 실제 적재
python3 scripts/load_openr1_verifiable.py --limit 100 --chunk-size 50

# 본 적재 (offset으로 나눠서 반복)
python3 scripts/load_openr1_verifiable.py --offset 0 --limit 500 --chunk-size 50
python3 scripts/load_openr1_verifiable.py --offset 500 --limit 500 --chunk-size 50
```

주의:
- 운영에서 `--truncate` 사용 금지

## 4) starter code 생성

```bash
python3 scripts/generate_problem_language_profiles.py --limit 2000
```

## 5) 한국어 번역본 적재

원문 `problems`는 유지하고, 번역은 `problem_translations` 테이블에 upsert 됩니다.

```bash
python3 -m pip install -U boto3 "psycopg[binary]"

# dry-run
python3 scripts/translate_problems_ko.py --dry-run --limit 50

# 실제 적재
python3 scripts/translate_problems_ko.py --limit 200 --chunk-size 50
```

옵션 예시:

```bash
# 이미 번역된 문제도 강제 재번역
python3 scripts/translate_problems_ko.py --force --limit 200
```

번역 파이프라인(placeholder 보호/후처리) 로직을 변경한 뒤에는
기존 `source_hash`와 동일해도 품질 개선본을 덮어써야 하므로 `--force` 실행을 권장합니다.

## 6) 점검 SQL

```sql
SELECT COUNT(*) AS problems_count FROM problems;
SELECT COUNT(*) AS test_cases_count FROM test_cases;
SELECT COUNT(*) AS translated_ko_count
FROM problem_translations
WHERE language_code = 'ko';
```
