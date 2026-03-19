package com.back.domain.matching.queue.model;

import java.time.LocalDateTime;

/**
 * 현재 대기열에서 기다리고 있는 사용자 정보
 */
public class WaitingUser {

    private final Long userId;
    private final QueueKey queueKey;
    private final LocalDateTime joinedAt;

    public WaitingUser(Long userId, QueueKey queueKey) {
        this.userId = userId;
        this.queueKey = queueKey;
        this.joinedAt = LocalDateTime.now();
    }

    public Long getUserId() {
        return userId;
    }

    public QueueKey getQueueKey() {
        return queueKey;
    }

    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }
}
