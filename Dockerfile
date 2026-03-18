# syntax=docker/dockerfile:1.7
# =========================
# 1. Build Stage
# =========================
# pgvector/pgvector:pg16 — pgvector 포함 커스텀 이미지
FROM pgvector/pgvector:pg16 AS builder

# Eclipse Temurin 21 설치 (bookworm 기본 repo에 openjdk-21 없음 → 바이너리 직접 다운로드)
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && curl -sL "https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.5%2B11/OpenJDK21U-jdk_x64_linux_hotspot_21.0.5_11.tar.gz" \
    -o /tmp/jdk21.tar.gz \
 && tar xz -C /opt -f /tmp/jdk21.tar.gz \
 && mv "/opt/jdk-21.0.5+11" /opt/java \
 && rm /tmp/jdk21.tar.gz \
 && rm -rf /var/lib/apt/lists/*

ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:${PATH}"

WORKDIR /app

# 의존성 캐시 레이어 (소스 변경 시에도 캐시 유지)
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src src

# PG 초기화 → 시작 → 테스트 DB 생성 + pgvector 확장 → 빌드(테스트 포함) → PG 종료
RUN set -e \
 && PGDATA=/var/lib/postgresql/data \
 && mkdir -p "$PGDATA" && chown postgres:postgres "$PGDATA" \
 && su postgres -c "initdb -D $PGDATA --auth=trust" \
 && su postgres -c "pg_ctl -D $PGDATA start -o '-c listen_addresses=localhost' -w" \
 && su postgres -c "psql -c \"CREATE USER algo_user WITH PASSWORD 'algo_pass';\"" \
 && su postgres -c "psql -c \"CREATE DATABASE algo_db OWNER algo_user;\"" \
 && su postgres -c "psql -d algo_db -c \"CREATE EXTENSION IF NOT EXISTS vector;\"" \
 && ./gradlew build --no-daemon \
 && su postgres -c "pg_ctl -D $PGDATA stop -w"

# =========================
# 2. Layer Extract Stage
# =========================
FROM eclipse-temurin:21-jdk AS extractor

WORKDIR /app
COPY --from=builder /app/build/libs /app/libs
RUN find /app/libs -name "*.jar" ! -name "*plain*" -exec cp {} /app/app.jar \; \
 && java -Djarmode=layertools -jar app.jar extract

# =========================
# 3. Runtime Stage
# =========================
FROM eclipse-temurin:21-jre

ENV LANG=ko_KR.UTF-8
ENV LC_ALL=ko_KR.UTF-8
ENV JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

WORKDIR /app

# Spring Boot layers (변경 빈도 낮은 순 → 높은 순으로 배치해 캐시 효율 극대화)
COPY --from=extractor /app/dependencies/ ./
COPY --from=extractor /app/snapshot-dependencies/ ./
COPY --from=extractor /app/spring-boot-loader/ ./
COPY --from=extractor /app/application/ ./

ENTRYPOINT ["java", \
  "-XX:+UseSerialGC", \
  "-XX:MaxRAMPercentage=55", \
  "-XX:InitialRAMPercentage=25", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-XX:+AlwaysPreTouch", \
  "-Dspring.profiles.active=prod", \
  "org.springframework.boot.loader.launch.JarLauncher"]
