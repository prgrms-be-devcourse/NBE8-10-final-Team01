package com.back.domain.matching.queue.dto;

/**
 * v2 matches/me 응답에서 프론트가 직접 해석할 상태값
 *
 * 내부 저장소의 MatchSessionStatus와 이름이 일부 비슷하지만,
 * 이 enum은 "프론트 화면이 무엇을 보여줘야 하는가" 관점의 상태다.
 */
public enum MatchStatus {
    IDLE,
    ACCEPT_PENDING,
    ROOM_READY,
    EXPIRED,
    CANCELLED
}
