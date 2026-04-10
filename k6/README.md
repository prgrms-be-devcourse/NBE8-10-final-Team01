# k6 로컬 부하테스트

## 1) 사전 준비

- 백엔드 로컬 실행 (`http://localhost:8080`)
- 테스트 계정 준비 (`K6_EMAIL`, `K6_PASSWORD`)

> 로그인은 쿠키 기반이므로, 각 VU에서 최초 1회 로그인 후 인증 API를 호출합니다.

## 2) 설치

```bash
brew install k6
k6 version
```

## 3) 환경변수

```bash
export BASE_URL=http://localhost:8080
export K6_EMAIL=admin@gmail.com
export K6_PASSWORD=admin1234

# 선택: 문제 ID를 고정하고 싶을 때
export K6_PROBLEM_ID=1
```

`K6_PROBLEM_ID`를 지정하지 않으면 `/api/v1/problems?page=0&size=1`에서 첫 문제를 자동 사용합니다.

## 4) 실행

```bash
k6 run k6/smoke.js
k6 run k6/load.js
k6 run k6/spike.js
```

## 5) 시나리오 설명

- `smoke.js`
  - 빠른 헬스체크 성격
  - 공개 API + 인증 API 기본 응답 확인
- `load.js`
  - 점진적 증가 후 유지하는 일반 부하
  - 목록/상세/내정보/solo run 혼합
- `spike.js`
  - 단시간 급증 트래픽 대응 확인

## 6) 참고

- 결과에서 먼저 볼 지표
  - `http_req_failed`
  - `http_req_duration p(95), p(99)`
- 메트릭 대시보드는 `docs/monitoring-k6-guide.md`를 참고하세요.
