# 모니터링 + k6 부하테스트 가이드

이 문서는 현재 프로젝트 백엔드(Spring Boot) 기준으로, 다음 두 가지를 빠르게 적용/운영하기 위한 정리입니다.

- 모니터링: `Actuator + Prometheus + Grafana`
- 로컬 부하테스트: `k6`

## 1. 현재 백엔드 준비 상태

아래 항목이 코드에 반영되어 있어야 Grafana에서 메트릭을 볼 수 있습니다.

- 의존성
  - `org.springframework.boot:spring-boot-starter-actuator`
  - `io.micrometer:micrometer-registry-prometheus`
- 설정 (`application-dev.yml`, `application-prod.yml`)
  - `management.endpoints.web.exposure.include: health, prometheus`
  - `management.prometheus.metrics.export.enabled: true`
  - `management.metrics.distribution.percentiles-histogram.http.server.requests: true`
- 보안 허용 (`SecurityConfig`)
  - `/actuator/health`
  - `/actuator/prometheus`

## 2. 모니터링 점검 순서

### 2.1 백엔드 메트릭 엔드포인트 확인

로컬:

```bash
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:8080/actuator/prometheus | head -n 20
```

배포:

```bash
curl -s http://app_blue:8080/actuator/prometheus | head -n 20
```

### 2.2 Prometheus 타겟 확인

`http://<EC2_IP>:9090/targets`에서 아래가 `UP`인지 확인합니다.

- `prometheus`
- `app-blue` (`app_blue:8080`)
- `app-green` (`app_green:8080`)

### 2.3 Grafana 데이터소스 확인

1. `http://<EC2_IP>:3000` 접속
2. `admin / password_1` 값으로 로그인
3. `Data Sources > Prometheus > Save & test`

## 3. Grafana 추천 패널 (PromQL)

### 3.1 트래픽 (RPS)

```promql
sum(rate(http_server_requests_seconds_count[1m]))
```

### 3.2 평균 응답시간 (초)

```promql
sum(rate(http_server_requests_seconds_sum[1m]))
/
sum(rate(http_server_requests_seconds_count[1m]))
```

### 3.3 p95 응답시간 (초)

```promql
histogram_quantile(
  0.95,
  sum(rate(http_server_requests_seconds_bucket[5m])) by (le)
)
```

### 3.4 5xx 비율

```promql
sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m]))
/
sum(rate(http_server_requests_seconds_count[5m]))
```

### 3.5 JVM Heap 사용량 (바이트)

```promql
sum(jvm_memory_used_bytes{area="heap"})
```

## 4. Node Exporter 추가 (호스트 메트릭)

EC2에서 아래처럼 실행하면 호스트 CPU/메모리/디스크 지표를 수집할 수 있습니다.

```bash
docker run -d \
  --name node_exporter \
  --restart unless-stopped \
  --network common \
  -p 9100:9100 \
  --pid="host" \
  -v "/:/host:ro,rslave" \
  quay.io/prometheus/node-exporter:latest \
  --path.rootfs=/host
```

Prometheus 설정(`prometheus.yml`)에 타겟을 추가합니다.

```yaml
scrape_configs:
  - job_name: "node"
    static_configs:
      - targets: ["node_exporter:9100"]
```

적용 후:

```bash
docker restart prometheus_1
curl -s http://localhost:9090/api/v1/targets | head -c 1200
```

Node Exporter가 `UP`이면 Grafana에서 아래 지표를 사용할 수 있습니다.

- `rate(node_cpu_seconds_total{mode!="idle"}[1m])`
- `node_memory_MemAvailable_bytes`
- `node_filesystem_avail_bytes`

## 5. 로컬 k6 부하테스트

## 5.1 설치

macOS(Homebrew):

```bash
brew install k6
```

확인:

```bash
k6 version
```

## 5.2 스크립트 위치

- `k6/smoke.js`: 짧은 기본 점검
- `k6/load.js`: 지속 부하 테스트
- `k6/spike.js`: 급격한 트래픽 증가 테스트

## 5.3 실행 전 환경변수

```bash
export BASE_URL=http://localhost:8080
export K6_EMAIL=admin@gmail.com
export K6_PASSWORD=admin1234
# 선택: 인증 시나리오에서 사용할 문제 ID
export K6_PROBLEM_ID=1
```

`K6_EMAIL/K6_PASSWORD` 계정은 미리 가입되어 있어야 합니다.

## 5.4 실행 명령

```bash
k6 run k6/smoke.js
k6 run k6/load.js
k6 run k6/spike.js
```

## 6. 권장 기준(초안)

- `http_req_failed`: `< 1%`
- 일반 조회 API `p95`: `< 700ms`
- 채점 요청 API `p95`: `< 2s` (비동기 큐 진입 기준)

## 7. 주의사항

- k6는 로컬 또는 별도 로드 제너레이터에서 실행하세요.
  - 앱 서버에서 직접 부하를 걸면 CPU/메모리 경쟁으로 결과가 왜곡될 수 있습니다.
- 테스트 전/후 DB 상태를 확인하고, 운영 DB에는 직접 대량 부하를 주지 않는 것을 권장합니다.
- WebSocket 배틀 시나리오는 HTTP 시나리오 안정화 후 2차로 분리해 진행하세요.
