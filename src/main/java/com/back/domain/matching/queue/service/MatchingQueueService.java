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
        QueueKey queueKey = new QueueKey(request.getCategory(), request.getDifficulty());

        // putIfAbsent를 사용하여 중복 참가를 원자적으로 방지합니다.
        if (userQueueMap.putIfAbsent(userId, queueKey) != null) {
            throw new IllegalStateException("이미 매칭 대기열에 참가 중인 사용자입니다.");
        }

        // 해당 큐가 없으면 새로 만들고, 있으면 기존 큐를 가져온다.
        Deque<WaitingUser> queue = waitingQueues.computeIfAbsent(queueKey, key -> new ConcurrentLinkedDeque<>());

        WaitingUser waitingUser = new WaitingUser(userId, queueKey);

        // 큐의 맨 뒤에 추가
        queue.addLast(waitingUser);

        return new QueueStatusResponse(
                "매칭 대기열에 참가했습니다.", queueKey.category(), queueKey.difficulty().name(), queue.size());
    }

    public QueueStatusResponse cancelQueue(Long userId) {
        // 1. 유저가 어느 큐에 들어가 있는지 찾는다.
        QueueKey queueKey = userQueueMap.get(userId);

        // 2. 대기열에 없는 유저면 예외 발생
        if (queueKey == null) {
            throw new IllegalStateException("현재 매칭 대기열에 참가 중이 아닙니다.");
        }

        // 3. 해당 큐를 가져온다.
        Deque<WaitingUser> queue = waitingQueues.get(queueKey);

        // 4. 큐 자체가 없으면 비정상 상태
        if (queue == null) {
            userQueueMap.remove(userId);
            throw new IllegalStateException("대기열 정보를 찾을 수 없습니다.");
        }

        // 5. 큐에서 해당 userId를 가진 WaitingUser 제거
        boolean removed = queue.removeIf(waitingUser -> waitingUser.getUserId().equals(userId));

        // 6. userQueueMap에서도 제거
        userQueueMap.remove(userId);

        // 7. 큐에서 제거 실패 시 예외
        if (!removed) {
            throw new IllegalStateException("대기열에서 사용자를 제거하지 못했습니다.");
        }

        // 8. 해당 큐가 비어 있으면 삭제하고, 안 비어 있으면 그대로 둬라
        waitingQueues.computeIfPresent(queueKey, (key, q) -> q.isEmpty() ? null : q);

        // 9. 응답 반환
        return new QueueStatusResponse(
                "매칭 대기열에서 취소되었습니다.", queueKey.category(), queueKey.difficulty().name(), queue.size());
    }

    // 테스트에서 큐 정리 여부를 확인하기 위한 package-private 조회 메서드
    boolean hasQueue(QueueKey queueKey) {
        return waitingQueues.containsKey(queueKey);
    }
}
