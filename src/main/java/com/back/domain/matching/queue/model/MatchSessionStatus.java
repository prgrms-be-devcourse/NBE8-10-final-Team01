package com.back.domain.matching.queue.model;

/**
 * 매치 세션의 현재 상태를 나타낸다.
 *
 * 이번 단계에서는 프론트/API를 바꾸지 않는 것이 목표이므로
 * 최소 상태만 먼저 도입한다.
 *
 * - CLOSED:  모든 참가자의 매칭 연결이 정리되어 더 이상 조회 대상이 아닌 상태
 *
 * 이후 롤 매칭 단계로 확장할 때
 * ACCEPT_PENDING, DECLINED, EXPIRED 같은 상태를 여기에 추가할 수 있다.
 *
 * 현재 v2 ready-check 1차 구현에서는 아래 상태를 함께 사용한다.
 * - ACCEPT_PENDING: 4명 매칭 성사 후 수락/거절을 기다리는 상태
 * - ROOM_READY: 전원 수락과 방 생성까지 끝난 상태
 * - EXPIRED: 수락 시간이 만료된 상태
 * - CANCELLED: 누군가 거절했거나 방 생성이 실패해 종료된 상태
 */
public enum MatchSessionStatus {
    // v2 ready-check가 시작됐지만 아직 전원 수락 전인 상태
    ACCEPT_PENDING,
    // 전원 수락은 끝났지만 room 생성 권한을 한 요청이 선점한 내부 상태
    ROOM_CREATING,
    // 전원 수락이 끝나 roomId까지 연결된 상태
    ROOM_READY,
    // 제한 시간이 지나 ready-check가 무효가 된 상태
    EXPIRED,
    // 누군가 거절했거나 room 생성 실패로 세션이 종료된 상태
    CANCELLED,
    // 모든 참조가 정리되어 더 이상 조회 대상이 아닌 상태
    CLOSED
}
