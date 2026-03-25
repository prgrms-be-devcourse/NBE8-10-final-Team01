# Judge0 Self-host 구축 및 제출/채점 가이드

## 1. 개요

이 문서는 **Windows 11 + WSL2 + Docker Desktop** 환경에서 **Judge0 CE를 Self-host 방식으로 실행**하고, 실제로 코드 제출 및 채점 결과를 확인하는 과정을 처음부터 끝까지 정리한 README입니다.

이 가이드의 목표는 다음과 같습니다.

- Judge0를 로컬 환경에서 직접 실행
- 코드 제출 API 호출
- 실행 결과 확인
- `expected_output` 기반 채점 확인
- 여러 테스트케이스를 한 번에 제출하는 batch 채점 확인
- `Accepted`, `Wrong Answer`, `Runtime Error` 등의 상태를 실제로 검증

---

## 2. 환경

### Host OS
- Windows 11

### Linux Subsystem
- WSL2

### Container Runtime
- Docker Desktop

### Linux Distribution
- Ubuntu (WSL 내부)

### Judge Engine
- Judge0 CE Self-host

---

## 3. 핵심 결론

처음에는 Windows 경로에서 Judge0를 실행했지만, 실제 제출 시 다음과 같은 문제가 발생할 수 있습니다.

```text
No such file or directory @ rb_sysopen - /box/script.py
status: Internal Error
```

즉,

- `/languages` API는 정상 동작
- `/submissions` 요청도 정상 진입
- 하지만 실제 코드 실행 단계에서 실패

이 문제는 **Windows 경로 + Docker Desktop + WSL2 조합에서 발생하는 경로/샌드박스 문제**로 볼 수 있습니다.

### 해결 방법
- Docker Desktop은 그대로 사용
- Judge0 프로젝트는 **Ubuntu WSL 내부 Linux 파일시스템 경로**에서 실행

즉, 아래처럼 운영해야 합니다.

- 비권장: `C:\Users\...\judge0`
- 권장: `~/judge0`

---

## 4. 전체 구조

최종적으로는 아래 구조로 구성합니다.

- Windows 11
    - Docker Desktop 실행
- WSL2
    - Ubuntu 설치
- Ubuntu 내부
    - `~/judge0` 경로에 Judge0 프로젝트 clone
    - Docker Desktop 엔진을 통해 컨테이너 실행

즉, **Docker Engine은 Docker Desktop**, **프로젝트 실행 위치는 Ubuntu WSL 내부**인 구조입니다.

---

## 5. 사전 준비

### 5-1. WSL 상태 확인

PowerShell에서 다음 명령어로 WSL 상태를 확인합니다.

```powershell
wsl --version
wsl -l -v
```

예시:

```text
WSL 버전: 2.x.x.x
커널 버전: x.x.x.x
...
```

```text
  NAME              STATE           VERSION
* docker-desktop    Running         2
```

Ubuntu가 없다면 설치가 필요합니다.

---

### 5-2. Ubuntu 설치

PowerShell에서 아래 명령어를 실행합니다.

```powershell
wsl --install -d Ubuntu
```

설치 후 Ubuntu 최초 실행 및 사용자 생성까지 완료합니다.

`Enter new UNIX username:`

이건  
`WSL Ubuntu 안에서 사용할 사용자 이름을 입력하세요`  
라는 의미야.

여기서 적는 건 예를 들면 이런 거 가능해:

- `admin`
- `user`
- `han`
- `dev`
- `ubuntu`

보통은 영문 소문자로 간단하게 적는 게 좋아.  
띄어쓰기, 한글, 특수문자는 피하는 게 좋고.

예시:

`han`

입력하고 엔터 치면,  
다음엔 보통 아래처럼 비밀번호도 만들라고 나와.

`Enter new UNIX password:`

중요한 점:

- 이건 윈도우 로그인 계정이랑 별개
- 우분투 안에서만 쓰는 계정
- 윈도우 이름이랑 똑같지 않아도 됨

그리고 비밀번호 입력할 때는  
쳐도 화면에 아무것도 안 보이는 게 정상이야.  
별표도 안 뜰 수 있는데 그냥 입력하고 엔터 치면 돼.

추천 예시:

- `admin`
- `han`

같이 짧게 만들면 돼.

지금 바로 해야 할 건:

1. 사용자 이름 입력
2. 엔터
3. 비밀번호 입력
4. 비밀번호 한 번 더 입력

그러면 설치가 마무리돼.

설치가 끝나면 다시 확인합니다.

```powershell
wsl -l -v
```

정상 예시:

```text
  NAME              STATE           VERSION
* Ubuntu            Running         2
  docker-desktop    Running         2
```

---

### 5-3. Docker Desktop 설정

Docker Desktop에서 아래 항목들을 확인합니다.

#### 필수 확인 항목
- Docker Desktop 실행 중
- Linux containers 모드
- WSL 2 based engine 사용
- Ubuntu WSL integration 활성화

#### 설정 위치
- `Settings`
- `Resources`
- `WSL Integration`
- Ubuntu 활성화

---

## 6. Judge0 프로젝트 준비

Ubuntu 터미널에서 아래 명령어를 실행합니다.

```bash
mkdir -p ~/judge0
cd ~/judge0
git clone https://github.com/judge0/judge0.git .
```

### 중요
- 반드시 `~/judge0` 같은 **Ubuntu 내부 경로**에서 작업합니다.
- `/mnt/c/...` 또는 `C:\...` 기반 경로는 사용하지 않는 것이 좋습니다.

---

## 7. 설정 파일 수정

Judge0 설정 파일인 `judge0.conf`를 수정해야 합니다.

### 7-1. 설정 파일 백업

```bash
cp judge0.conf judge0.conf.bak
```

### 7-2. 설정 파일 열기

```bash
nano judge0.conf
```

### 7-3. 최소 필수 설정

아래 항목들을 확인하고 비어 있지 않게 설정합니다.

```env
REDIS_HOST=redis
REDIS_PORT=6379
REDIS_PASSWORD=redis1234

POSTGRES_HOST=db
POSTGRES_PORT=5432
POSTGRES_DB=judge0
POSTGRES_USER=judge0
POSTGRES_PASSWORD=judge01234
```

### 7-4. 추가 권장 설정

아래 값들도 함께 확인합니다.

```env
JUDGE0_TELEMETRY_ENABLE=false
ENABLE_WAIT_RESULT=true
RAILS_ENV=production
```

### 7-5. 각 설정 의미

#### `JUDGE0_TELEMETRY_ENABLE=false`
Judge0 익명 텔레메트리 전송 기능 비활성화

#### `ENABLE_WAIT_RESULT=true`
`/submissions?wait=true` 요청 허용  
즉, 제출 후 결과를 바로 응답받을 수 있도록 하는 옵션

#### `RAILS_ENV=production`
Judge0 서버를 production 모드로 실행

### 7-6. 주의사항

- `POSTGRES_PASSWORD`는 비워두면 안 됩니다.
- `REDIS_PASSWORD`도 비워두면 안 됩니다.
- Windows에서 수정한 설정 파일을 그대로 가져오면 CRLF 문제로 shell script가 깨질 수 있습니다.
- 가능하면 Ubuntu 내부에서 직접 수정하는 것이 안전합니다.

---

## 8. Docker 권한 문제 해결

Ubuntu에서 `docker compose up -d` 실행 시 아래와 같은 오류가 발생할 수 있습니다.

```text
permission denied while trying to connect to the Docker daemon socket
```

이 경우 현재 사용자에게 Docker 소켓 접근 권한이 없는 것입니다.

### 해결 방법

```bash
sudo usermod -aG docker $USER
newgrp docker
```

이후 아래 명령어로 정상 동작 여부를 확인합니다.

```bash
docker version
docker compose version
```

그래도 반영되지 않으면 Ubuntu 터미널을 종료 후 다시 열거나, Windows PowerShell에서 아래 명령을 실행합니다.

```powershell
wsl --shutdown
```

그 후 Ubuntu를 다시 실행합니다.

---

## 9. Judge0 실행

Ubuntu에서 다음 명령어를 실행합니다.

```bash
cd ~/judge0
docker compose down -v
docker compose up -d
```

### 컨테이너 상태 확인

```bash
docker compose ps
```

### 로그 확인

```bash
docker compose logs --tail=100 db
docker compose logs --tail=100 server
docker compose logs --tail=100 worker
```

---

## 10. 정상 동작 확인

### 10-1. 언어 목록 조회

Judge0 서버가 정상적으로 올라왔는지 먼저 확인합니다.

```bash
curl http://localhost:2358/languages
```

정상이라면 JSON 배열 형태의 언어 목록이 출력됩니다.

예시:

```json
[
  {"id":45,"name":"Assembly (NASM 2.14.02)"},
  {"id":71,"name":"Python (3.8.1)"},
  {"id":62,"name":"Java (OpenJDK 13.0.1)"}
]
```

이 단계가 성공하면 아래가 정상임을 의미합니다.

- API 서버 정상
- DB/Redis 기본 연결 정상
- 포트 노출 정상

---

## 11. 단일 제출 테스트

가장 간단한 예제로 실제 제출이 되는지 확인합니다.

### 예제 문제
두 정수를 입력받아 합을 출력

### 예제 코드

```python
a, b = map(int, input().split())
print(a + b)
```

### 요청

```bash
curl -X POST "http://localhost:2358/submissions?wait=true" \
  -H "Content-Type: application/json" \
  -d '{"language_id":71,"source_code":"a, b = map(int, input().split())\nprint(a + b)","stdin":"3 5"}'
```

### 예상 응답

```json
{
  "stdout":"8\n",
  "time":"0.018",
  "memory":3464,
  "stderr":null,
  "token":"...",
  "compile_output":null,
  "message":null,
  "status":{
    "id":3,
    "description":"Accepted"
  }
}
```

### 의미

- `stdout`: 프로그램 출력 결과
- `time`: 실행 시간
- `memory`: 사용 메모리
- `status.description`: 실행 결과 상태

---

## 12. Judge0가 해주는 채점 범위

Judge0는 기본적으로 **코드 실행 엔진**입니다.

즉 아래를 수행합니다.

- 코드 실행
- 입력 전달
- 출력 수집
- 컴파일/런타임 상태 반환

하지만, `expected_output`을 함께 보내면 **실제 출력과 정답 출력 비교까지 수행**할 수 있습니다.

즉:

- 출력이 같으면 `Accepted`
- 출력이 다르면 `Wrong Answer`

---

## 13. 단일 테스트케이스 채점 예시

### 정답 코드

```python
a, b = map(int, input().split())
print(a + b)
```

### 요청

```bash
curl -X POST "http://localhost:2358/submissions?wait=true" \
  -H "Content-Type: application/json" \
  -d '{"language_id":71,"source_code":"a, b = map(int, input().split())\nprint(a + b)","stdin":"3 5","expected_output":"8"}'
```

### 예상 결과

```json
{
  "stdout":"8\n",
  "status":{
    "id":3,
    "description":"Accepted"
  }
}
```

---

## 14. 여러 테스트케이스를 한 번에 제출하기

Judge0는 batch API를 지원합니다.  
즉 여러 개의 테스트케이스를 한 번에 제출할 수 있습니다.

### 예제: 3개 테스트케이스 제출

```bash
curl -X POST "http://localhost:2358/submissions/batch?base64_encoded=false" \
  -H "Content-Type: application/json" \
  -d '{
    "submissions": [
      {
        "language_id": 71,
        "source_code": "a, b = map(int, input().split())\nprint(a + b)",
        "stdin": "3 5",
        "expected_output": "8"
      },
      {
        "language_id": 71,
        "source_code": "a, b = map(int, input().split())\nprint(a + b)",
        "stdin": "10 20",
        "expected_output": "30"
      },
      {
        "language_id": 71,
        "source_code": "a, b = map(int, input().split())\nprint(a + b)",
        "stdin": "7 8",
        "expected_output": "15"
      }
    ]
  }'
```

### 응답

Judge0는 즉시 최종 결과를 반환하는 것이 아니라, 각 submission에 대한 token 목록을 반환합니다.

예시:

```json
[
  {"token":"token1"},
  {"token":"token2"},
  {"token":"token3"}
]
```

---

## 15. 배치 결과 조회

위에서 받은 token들을 이용해 batch 결과를 조회합니다.

```bash
curl "http://localhost:2358/submissions/batch?tokens=token1,token2,token3&base64_encoded=false&fields=token,stdout,stderr,status"
```

### 참고
- 너무 빨리 조회하면 아직 처리 중일 수 있습니다.
- 이 경우 1~2초 뒤 다시 조회하면 됩니다.

---

## 16. 일부러 1개 틀린 테스트케이스 만들기

채점이 정상 동작하는지 확인하기 위해, 4개 테스트케이스 중 1개를 일부러 틀리게 구성할 수 있습니다.

### 예제 제출

```bash
curl -X POST "http://localhost:2358/submissions/batch?base64_encoded=false" \
  -H "Content-Type: application/json" \
  -d '{
    "submissions": [
      {
        "language_id": 71,
        "source_code": "a, b = map(int, input().split())\nprint(a + b)",
        "stdin": "3 5",
        "expected_output": "8"
      },
      {
        "language_id": 71,
        "source_code": "a, b = map(int, input().split())\nprint(a + b)",
        "stdin": "10 20",
        "expected_output": "30"
      },
      {
        "language_id": 71,
        "source_code": "a, b = map(int, input().split())\nprint(a + b)",
        "stdin": "7 8",
        "expected_output": "100"
      },
      {
        "language_id": 71,
        "source_code": "a, b = map(int, input().split())\nprint(a + b)",
        "stdin": "1 2",
        "expected_output": "3"
      }
    ]
  }'
```

여기서 3번째 케이스만 일부러 틀렸습니다.

- 입력: `7 8`
- 실제 정답: `15`
- 기대 출력: `100`

---

## 17. 실제 배치 결과 조회 예시

예를 들어 아래와 같은 token 4개를 받았다고 가정합니다.

- `token1-1234-tttt-1234-456158tdqw86`
- `token2-1234-tttt-1234-456158tdqw86`
- `token3-1234-tttt-1234-456158tdqw86`
- `token4-1234-tttt-1234-456158tdqw86`

그러면 조회는 다음과 같이 합니다.

```bash
curl "http://localhost:2358/submissions/batch?tokens=token1-1234-tttt-1234-456158tdqw86,token2-1234-tttt-1234-456158tdqw86,token3-1234-tttt-1234-456158tdqw86,token4-1234-tttt-1234-456158tdqw86&base64_encoded=false&fields=token,stdout,stderr,message,status"
```

### 실제 결과 예시

```json
{
  "submissions":[
    {
      "stdout":"8\n",
      "stderr":null,
      "token":"token1-1234-tttt-1234-456158tdqw86",
      "message":null,
      "status":{"id":3,"description":"Accepted"}
    },
    {
      "stdout":"30\n",
      "stderr":null,
      "token":"token2-1234-tttt-1234-456158tdqw86",
      "message":null,
      "status":{"id":3,"description":"Accepted"}
    },
    {
      "stdout":"15\n",
      "stderr":null,
      "token":"token3-1234-tttt-1234-456158tdqw86",
      "message":null,
      "status":{"id":4,"description":"Wrong Answer"}
    },
    {
      "stdout":"3\n",
      "stderr":null,
      "token":"token4-1234-tttt-1234-456158tdqw86",
      "message":null,
      "status":{"id":3,"description":"Accepted"}
    }
  ]
}
```

---

## 18. 배치 결과 해석

| 케이스 | 입력(stdin) | 실제 출력(stdout) | 결과 |
|---|---|---|---|
| 1 | `3 5` | `8` | Accepted |
| 2 | `10 20` | `30` | Accepted |
| 3 | `7 8` | `15` | Wrong Answer |
| 4 | `1 2` | `3` | Accepted |

### 최종 요약
- 총 테스트케이스: 4개
- 통과: 3개
- 실패: 1개
- 최종 제출 결과: `Wrong Answer`

---

## 19. 실제 서비스에서의 채점 흐름

실제 서비스에서는 일반적으로 아래 순서로 동작합니다.

### 1. 문제 조회
문제에 연결된 테스트케이스 목록 조회

예:
- testcase1: input / expected_output
- testcase2: input / expected_output
- testcase3: input / expected_output

### 2. 사용자 코드 제출
사용자가 아래 정보를 제출

- 문제 ID
- 언어
- 소스코드

### 3. Judge0 요청 생성
각 테스트케이스를 Judge0 batch submission 형태로 변환

### 4. Judge0 실행
Judge0가 각 submission 실행 후 결과 반환

### 5. 결과 집계
서버는 각 케이스 결과를 모아서 최종 판정을 생성

예:
- 전부 Accepted → 최종 Accepted
- 하나라도 Wrong Answer → 최종 Wrong Answer
- Runtime Error 존재 → 최종 Runtime Error
- Time Limit Exceeded 존재 → 최종 TLE

### 6. 제출 기록 저장
DB에 다음 정보를 저장 가능

- 제출 코드
- 언어
- 실행 시간
- 메모리
- 각 케이스 결과
- 최종 판정

---

## 20. 추천 구현 방식

### 추천 방식
- 테스트케이스가 1개 이상이면 `batch` API 사용
- 각 테스트케이스마다 아래 정보를 포함
    - `stdin`
    - `expected_output`
    - `source_code`
    - `language_id`
- Judge0가 각 submission 결과를 반환
- 서버는 그 결과를 집계하여 최종 판정을 생성

### 왜 서버 집계가 필요한가
Judge0는 각 submission에 대한 결과는 잘 반환하지만,  
최종적으로 “이 제출이 문제 전체를 통과했는가”를 결정하는 정책은 보통 서비스 서버가 담당합니다.

예:
- hidden testcase 포함 여부
- sample testcase 노출 여부
- 부분 점수
- 실패 메시지 정책
- 어느 케이스에서 틀렸는지 노출 여부

---

## 21. 자주 발생하는 문제

### 21-1. `/box/script.py` 오류

#### 증상

```text
No such file or directory @ rb_sysopen - /box/script.py
Internal Error
```

#### 원인
Windows 경로 기반 실행 시 Judge0 샌드박스 파일 처리 문제

#### 해결
Judge0 프로젝트를 Ubuntu WSL 내부 Linux 경로에서 실행

---

### 21-2. Docker 소켓 권한 오류

#### 증상

```text
permission denied while trying to connect to the Docker daemon socket
```

#### 원인
현재 사용자에게 Docker 소켓 접근 권한이 없음

#### 해결

```bash
sudo usermod -aG docker $USER
newgrp docker
```

---

### 21-3. Postgres 비밀번호 관련 오류

#### 증상
DB 컨테이너가 시작되지 않음

#### 원인
`judge0.conf`에서 `POSTGRES_PASSWORD`가 비어 있음

#### 해결

```env
POSTGRES_PASSWORD=judge01234
```

반드시 비워두지 않도록 설정

---

### 21-4. CRLF 관련 오류

#### 증상

```text
$'\r': command not found
seq: invalid floating point argument: '\r'
```

#### 원인
Windows 줄바꿈(CRLF)로 저장된 설정 파일

#### 해결
Ubuntu 내부에서 설정 파일을 다시 편집하거나 LF 형식으로 저장

---

## 22. 추천 테스트 순서

처음 구축 후에는 아래 순서대로 테스트하는 것을 권장합니다.

### 1단계: 언어 목록 조회

```bash
curl http://localhost:2358/languages
```

### 2단계: 단일 제출 테스트

```bash
curl -X POST "http://localhost:2358/submissions?wait=true" \
  -H "Content-Type: application/json" \
  -d '{"language_id":71,"source_code":"print(input())","stdin":"hello"}'
```

### 3단계: expected_output 포함 테스트

```bash
curl -X POST "http://localhost:2358/submissions?wait=true" \
  -H "Content-Type: application/json" \
  -d '{"language_id":71,"source_code":"print(input())","stdin":"hello","expected_output":"hello"}'
```

### 4단계: batch 제출 테스트

```bash
curl -X POST "http://localhost:2358/submissions/batch?base64_encoded=false" \
  -H "Content-Type: application/json" \
  -d '{
    "submissions": [
      {
        "language_id": 71,
        "source_code": "a, b = map(int, input().split())\nprint(a + b)",
        "stdin": "3 5",
        "expected_output": "8"
      },
      {
        "language_id": 71,
        "source_code": "a, b = map(int, input().split())\nprint(a + b)",
        "stdin": "10 20",
        "expected_output": "30"
      }
    ]
  }'
```

---

## 23. 결론

이번 환경 검증을 통해 아래 사항을 확인했습니다.

- Judge0 Self-host는 Windows 11 + WSL2 + Docker Desktop 환경에서도 구성 가능
- 다만 프로젝트는 Windows 경로가 아니라 **Ubuntu WSL 내부 경로**에서 실행해야 안정적
- `/languages` 호출 정상 동작 확인
- 단일 제출 정상 동작 확인
- `expected_output` 기반 채점 정상 동작 확인
- batch submission을 통한 다중 테스트케이스 채점 정상 동작 확인

즉, 이후 서비스 서버에서는 다음 흐름으로 구현하면 됩니다.

1. 문제별 테스트케이스를 DB에서 조회
2. Judge0 batch API로 제출
3. Judge0 결과를 수집
4. 서버에서 최종 제출 결과를 집계하여 반환

현재 상태는 **실제 온라인 저지 기능을 구현할 수 있는 수준까지 환경 검증이 완료된 상태**입니다.

---

## 24. 명령어 모음

### Judge0 실행

```bash
cd ~/judge0
docker compose up -d
```

### 컨테이너 상태 확인

```bash
docker compose ps
```

### 로그 확인

```bash
docker compose logs --tail=100 server
docker compose logs --tail=100 worker
docker compose logs --tail=100 db
```

### 언어 목록 확인

```bash
curl http://localhost:2358/languages
```

### 단일 제출

```bash
curl -X POST "http://localhost:2358/submissions?wait=true" \
  -H "Content-Type: application/json" \
  -d '{"language_id":71,"source_code":"print(input())","stdin":"hello"}'
```

### 단일 채점

```bash
curl -X POST "http://localhost:2358/submissions?wait=true" \
  -H "Content-Type: application/json" \
  -d '{"language_id":71,"source_code":"print(input())","stdin":"hello","expected_output":"hello"}'
```

### 배치 제출

```bash
curl -X POST "http://localhost:2358/submissions/batch?base64_encoded=false" \
  -H "Content-Type: application/json" \
  -d '{
    "submissions": [
      {
        "language_id": 71,
        "source_code": "a, b = map(int, input().split())\nprint(a + b)",
        "stdin": "3 5",
        "expected_output": "8"
      }
    ]
  }'
```

### 배치 조회

```bash
curl "http://localhost:2358/submissions/batch?tokens=token1,token2,token3&base64_encoded=false&fields=token,stdout,stderr,status"
```
