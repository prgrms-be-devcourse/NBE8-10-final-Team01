package com.back.domain.matching.queue.service;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.stereotype.Service;

import com.back.domain.matching.queue.dto.QueueJoinRequest;
import com.back.domain.matching.queue.dto.QueueStatusResponse;
import com.back.domain.matching.queue.model.QueueKey;
import com.back.domain.matching.queue.model.WaitingUser;

@Service
public class MatchingQueueService {

    // TODO: 지금 현재 인메모리 방식임 redis로 전환하면 좋음 MVP 이기 때문
    /**
     * 카테고리 + 난이도별 대기열
     *
     * 예:
     * ARRAY + EASY -> [1번 유저, 7번 유저]
     * GRAPH + HARD -> [3번 유저]
     */
    private final Map<QueueKey, Deque<WaitingUser>> waitingQueues = new ConcurrentHashMap<>();

    /**
     * 특정 유저가 이미 대기열에 들어가 있는지 빠르게 확인하기 위한 맵
     *
     * 예:
     * 1L -> (ARRAY, EASY)
     * 7L -> (ARRAY, EASY)
     */
    private final Map<Long, QueueKey> userQueueMap = new ConcurrentHashMap<>();

    /**
     * 매칭 시작 요청 처리
     *
     * 1. 이미 대기열에 참가 중인지 확인
     * 2. category + difficulty 로 QueueKey 생성
     * 3. 해당 QueueKey의 큐가 없으면 생성
     * 4. 유저를 대기열에 추가
     * 5. userQueueMap에도 기록
     */
    public QueueStatusResponse joinQueue(Long userId, QueueJoinRequest request) {
        // 이미 대기열에 들어가 있는 유저는 다시 참가할 수 없다.
        if (userQueueMap.containsKey(userId)) {
            throw new IllegalStateException("이미 매칭 대기열에 참가 중인 사용자입니다.");
        }

        QueueKey queueKey = new QueueKey(request.getCategory(), request.getDifficulty());

        // 해당 큐가 없으면 새로 만들고, 있으면 기존 큐를 가져온다.
        Deque<WaitingUser> queue = waitingQueues.computeIfAbsent(queueKey, key -> new ConcurrentLinkedDeque<>());

        WaitingUser waitingUser = new WaitingUser(userId, queueKey);

        // 큐의 맨 뒤에 추가
        queue.addLast(waitingUser);

        // 유저가 어느 큐에 들어갔는지 기록
        userQueueMap.put(userId, queueKey);

        return new QueueStatusResponse(
                "매칭 대기열에 참가했습니다.", queueKey.category(), queueKey.difficulty().name(), queue.size());
    }
}
