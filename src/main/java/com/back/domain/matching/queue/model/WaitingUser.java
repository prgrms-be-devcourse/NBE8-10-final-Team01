package com.back.domain.matching.queue.model;

import java.time.LocalDateTime;

/**
 * 현재 매칭 대기열에서 기다리고 있는 사용자 정보를 저장하는 객체
 *
 * 이 객체는 DB에 저장되는 엔티티가 아니라,
 * 서버 메모리의 대기열 안에서만 사용되는 매칭용 모델이다.
 */
public class WaitingUser {

    // 대기 중인 사용자의 고유 ID
    private final Long userId;

    /**
     * ready-check 응답에서 회원 정보를 다시 조회하지 않도록 join 시점 nickname snapshot을 함께 보관한다.
     * 예: WaitingUser(1L, "m1", (DP, EASY)) 이면 이후 matches/me 에서도 "m1"을 그대로 쓸 수 있다.
     */
    private final String nickname;

    /**
     * 사용자가 어떤 조건의 큐에 들어갔는지 나타내는 값
     * 예: (ARRAY, EASY)
     */
    private final QueueKey queueKey;

    /**
     * 사용자가 대기열에 들어온 시각
     *
     * 나중에 먼저 들어온 사용자를 우선 매칭하거나,
     * 대기 시간을 계산할 때 활용할 수 있다.
     */
    private final LocalDateTime joinedAt;

    /**
     * WaitingUser 생성자
     *
     * 사용자가 매칭 시작 요청을 보내면
     * 해당 userId와 queueKey를 기반으로 대기 사용자 객체를 만든다.
     *
     * joinedAt은 객체가 생성되는 현재 시각으로 자동 저장한다.
     */
    public WaitingUser(Long userId, String nickname, QueueKey queueKey) {
        this.userId = userId;
        this.nickname = nickname;
        this.queueKey = queueKey;
        this.joinedAt = LocalDateTime.now();
    }

    // 대기 중인 사용자의 ID 반환
    public Long getUserId() {
        return userId;
    }

    public String getNickname() {
        return nickname;
    }

    // 사용자가 속한 큐 정보 반환
    public QueueKey getQueueKey() {
        return queueKey;
    }

    // 사용자가 대기열에 참가한 시각 반환
    public LocalDateTime getJoinedAt() {
        return joinedAt;
    }
}
