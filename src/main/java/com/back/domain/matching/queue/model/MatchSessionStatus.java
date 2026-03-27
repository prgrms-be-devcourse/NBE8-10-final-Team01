package com.back.domain.matching.queue.model;

/**
 * 매치 세션의 현재 상태를 나타낸다.
 *
 * 이번 단계에서는 프론트/API를 바꾸지 않는 것이 목표이므로
 * 최소 상태만 먼저 도입한다.
 *
 * - MATCHED: 4명 매칭이 완료되고 roomId까지 배정된 상태
 * - CLOSED:  모든 참가자의 매칭 연결이 정리되어 더 이상 조회 대상이 아닌 상태
 *
 * 이후 롤 매칭 단계로 확장할 때
 * ACCEPT_PENDING, DECLINED, EXPIRED 같은 상태를 여기에 추가할 수 있다.
 */
public enum MatchSessionStatus {
    MATCHED,
    CLOSED
}
