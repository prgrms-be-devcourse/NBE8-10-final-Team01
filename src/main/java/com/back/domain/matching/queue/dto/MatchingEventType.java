package com.back.domain.matching.queue.dto;

public enum MatchingEventType {
    QUEUE_STATE_CHANGED,
    READY_CHECK_STARTED,
    READY_DECISION_CHANGED,
    MATCH_CANCELLED,
    MATCH_EXPIRED,
    ROOM_READY
}
