package com.back.domain.matching.queue.model;

/**
 * ready-check에서 각 참가자가 현재 어떤 응답 상태인지 나타낸다.
 *
 * 이 값이 ready-check의 단일 원본이다.
 * acceptedCount, acceptedByMe 같은 값은 이 enum들의 집합에서 계산한다.
 */
public enum ReadyDecision {
    PENDING,
    ACCEPTED,
    DECLINED
}
