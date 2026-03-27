package com.back.domain.matching.queue.store;

import java.util.List;

import com.back.domain.matching.queue.dto.MatchStateResponse;
import com.back.domain.matching.queue.dto.QueueStateResponse;
import com.back.domain.matching.queue.model.QueueKey;
import com.back.domain.matching.queue.model.WaitingUser;

/**
 * 매칭 상태 저장소 추상화
 *
 * 지금은 인메모리 구현체를 사용하지만,
 * 이후 Redis 등으로 교체할 수 있도록 저장 경계를 먼저 만든다.
 */
public interface MatchStateStore {

    /**
     * cancel 결과를 서비스 계층이 응답 DTO로 조립하기 쉽도록 최소 정보만 담는다.
     */
    record CancelResult(QueueKey queueKey, int waitingCount) {}

    /**
     * 유저를 큐에 넣고 현재 큐 크기를 반환한다.
     * 이미 같은 유저가 대기 중이면 예외를 던진다.
     */
    int enqueue(Long userId, QueueKey queueKey);

    /**
     * SEARCHING 상태 유저를 큐에서 제거한다.
     */
    CancelResult cancel(Long userId);

    /**
     * 특정 큐에서 매칭 후보를 count명만큼 poll한다.
     * 인원이 부족하면 null을 반환한다.
     */
    List<WaitingUser> pollMatchCandidates(QueueKey queueKey, int count);

    /**
     * 방 생성 실패 시 poll했던 유저들을 큐로 되돌린다.
     */
    void rollbackPolledUsers(QueueKey queueKey, List<WaitingUser> users);

    /**
     * 방 생성 성공 시 유저들을 SEARCHING -> MATCHED 상태로 전환한다.
     */
    void markMatched(QueueKey queueKey, List<WaitingUser> matchedUsers, Long roomId);

    /**
     * 현재 해당 큐의 대기 인원 수를 반환한다.
     */
    int getWaitingCount(QueueKey queueKey);

    /**
     * queue/me 응답용 상태 조회
     */
    QueueStateResponse getQueueState(Long userId);

    /**
     * matches/me 응답용 상태 조회
     */
    MatchStateResponse getMatchState(Long userId);

    /**
     * 방 입장 성공 후 matched 상태를 정리한다.
     */
    void clearMatchedRoom(Long userId, Long roomId);

    /**
     * 기존 테스트 호환용
     */
    boolean hasQueue(QueueKey queueKey);
}
