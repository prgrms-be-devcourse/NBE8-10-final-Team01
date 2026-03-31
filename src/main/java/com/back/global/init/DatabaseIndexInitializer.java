package com.back.global.init;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseIndexInitializer {

    private final JdbcTemplate jdbcTemplate;

    /**
     * ddl-auto: update 가 테이블 스키마를 먼저 반영한 뒤 실행되도록 ApplicationReadyEvent 사용.
     * IF NOT EXISTS 로 멱등성 보장 — 서버 재시작 시 중복 실행돼도 안전.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void createIndexes() {
        jdbcTemplate.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_one_playing_per_member
                ON battle_participants (user_id)
                WHERE status = 'PLAYING'
                """);
        log.info("DB index 확인 완료 - uq_one_playing_per_member");
    }
}
