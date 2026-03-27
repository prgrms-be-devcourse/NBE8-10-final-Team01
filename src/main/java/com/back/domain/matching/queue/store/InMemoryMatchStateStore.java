package com.back.domain.matching.queue.store;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.back.domain.matching.queue.dto.MatchStateResponse;
import com.back.domain.matching.queue.dto.QueueStateResponse;
import com.back.domain.matching.queue.model.MatchSession;
import com.back.domain.matching.queue.model.MatchSessionStatus;
import com.back.domain.matching.queue.model.QueueKey;
import com.back.domain.matching.queue.model.WaitingUser;

/**
 * 현재 MVP용 인메모리 매칭 상태 저장소
 *
 * 역할:
 * - 실제 대기열(waitingQueues)
 * - 유저별 큐 참여 정보(userQueueMap)
 * - 유저별 매치 연결 정보(userMatchMap)
 * - 매치 그룹 상태 본문(matchSessionMap)
 *
 * 를 한 곳에 모아두고,
 * MatchingQueueService는 이 저장소를 통해서만 상태를 다루게 만든다.
 *
 * 즉,
 * 서비스는 "큐에 참가", "취소", "매칭 후보 추출", "매칭 완료 처리" 같은 흐름만 담당하고
 * 실제 자료구조(Map, Deque) 조작은 이 저장소가 담당한다.
 *
 * 지금은 인메모리 기반이지만,
 * 이후 Redis 등 외부 저장소로 바꾸더라도 서비스 로직 변경을 최소화하기 위해
 * 저장 경계를 먼저 분리한 구조다.
 */
@Component
public class InMemoryMatchStateStore implements MatchStateStore {

    /**
     * 카테고리 + 난이도별 실제 대기열
     *
     * 예:
     * ARRAY + EASY -> [1번 유저, 7번 유저]
     * GRAPH + HARD -> [3번 유저]
     *
     * key: QueueKey(category + difficulty)
     * value: 해당 조건으로 매칭을 기다리는 유저들의 순서 있는 큐
     */
    private final Map<QueueKey, Deque<WaitingUser>> waitingQueues = new ConcurrentHashMap<>();

    /**
     * 특정 유저가 현재 어느 큐에 들어가 있는지 저장
     *
     * 목적:
     * - 중복 참가 방지
     * - 유저 기준 취소/상태조회 빠른 처리
     *
     * 예:
     * 1L -> (ARRAY, EASY)
     * 7L -> (ARRAY, EASY)
     */
    private final Map<Long, QueueKey> userQueueMap = new ConcurrentHashMap<>();

    /**
     * 특정 유저가 현재 어느 매치 세션에 연결되어 있는지 찾는 인덱스
     *
     * 기존에는 userId -> roomId 만 저장했지만,
     * 이제는 유저가 "어느 매치 그룹에 속하는지"를 먼저 찾고
     * 실제 매치 상태는 MatchSession 본문에서 조회한다.
     *
     * 예:
     * 1L -> 10L
     * 2L -> 10L
     */
    private final Map<Long, Long> userMatchMap = new ConcurrentHashMap<>();

    /**
     * matchId 기준 실제 매치 그룹 상태를 저장하는 본문
     *
     * 지금 단계에서는 MATCHED 상태와 roomId만 담지만,
     * 이후 ready-check 단계로 가면 acceptedUsers, deadline 같은 상태를
     * 여기에 확장해서 넣을 수 있다.
     */
    private final Map<Long, MatchSession> matchSessionMap = new ConcurrentHashMap<>();

    /**
     * 인메모리 환경에서 matchId를 순차적으로 발급하기 위한 시퀀스
     *
     * 지금은 단일 서버 MVP 기준이라 AtomicLong으로 충분하고,
     * 이후 Redis 전환 시에는 외부 저장소 기준 ID 생성 전략으로 교체할 수 있다.
     */
    private final AtomicLong matchSequence = new AtomicLong(1L);

    /**
     * 유저를 대기열에 넣는다.
     *
     * 동작 순서:
     * 1. userQueueMap에 먼저 넣어서 "이미 대기 중인지" 검사
     * 2. queueKey에 해당하는 실제 큐를 가져오거나 새로 생성
     * 3. 큐 뒤쪽(addLast)에 유저를 넣음
     * 4. 현재 대기 인원 수 반환
     *
     * 왜 userQueueMap을 먼저 넣나?
     * -> 같은 유저가 동시에 여러 번 참가 요청하는 것을 빠르게 막기 위해서다.
     *
     * 왜 synchronized(queue)를 쓰나?
     * -> ConcurrentLinkedDeque 자체는 단건 연산은 안전하지만,
     *    add + size 같은 복합 로직은 한 덩어리로 묶어야 일관성이 맞기 때문이다.
     */
    @Override
    public int enqueue(Long userId, QueueKey queueKey) {
        if (userQueueMap.putIfAbsent(userId, queueKey) != null) {
            throw new IllegalStateException("이미 매칭 대기열에 참가 중인 사용자입니다.");
        }

        Deque<WaitingUser> queue = waitingQueues.computeIfAbsent(queueKey, key -> new ConcurrentLinkedDeque<>());

        synchronized (queue) {
            queue.addLast(new WaitingUser(userId, queueKey));
            return queue.size();
        }
    }

    /**
     * SEARCHING 상태 유저를 대기열에서 제거한다.
     *
     * 동작 순서:
     * 1. userQueueMap으로 유저가 어느 큐에 있는지 찾음
     * 2. 실제 큐를 가져옴
     * 3. 큐에서 해당 userId 제거
     * 4. userQueueMap에서도 제거
     * 5. 큐가 비었으면 waitingQueues에서도 제거
     *
     * 주의:
     * - queue.removeIf(...)
     * - userQueueMap.remove(...)
     * - queue.size()
     * - waitingQueues.remove(...)
     *
     * 이런 동작은 서로 논리적으로 연결되어 있기 때문에
     * 같은 큐 락 안에서 처리해야 상태 불일치를 줄일 수 있다.
     */
    @Override
    public CancelResult cancel(Long userId) {
        QueueKey queueKey = userQueueMap.get(userId);

        if (queueKey == null) {
            throw new IllegalStateException("현재 매칭 대기열에 참가 중이 아닙니다.");
        }

        Deque<WaitingUser> queue = waitingQueues.get(queueKey);

        if (queue == null) {
            userQueueMap.remove(userId);
            throw new IllegalStateException("대기열 정보를 찾을 수 없습니다.");
        }

        int currentSize;
        boolean removed;

        synchronized (queue) {
            removed = queue.removeIf(waitingUser -> waitingUser.getUserId().equals(userId));

            if (!removed) {
                throw new IllegalStateException("대기열에서 사용자를 제거하지 못했습니다.");
            }

            userQueueMap.remove(userId);
            currentSize = queue.size();

            if (currentSize == 0) {
                waitingQueues.remove(queueKey, queue);
            }
        }

        return new CancelResult(queueKey, currentSize);
    }

    /**
     * 특정 큐에서 매칭 후보를 count명만큼 앞에서부터 꺼낸다.
     *
     * 동작 방식:
     * - 큐가 없으면 null
     * - 큐 인원이 부족하면 null
     * - 충분하면 pollFirst()로 count명 추출
     *
     * 왜 pollFirst인가?
     * -> 먼저 들어온 유저가 먼저 매칭되도록 FIFO 순서를 유지하기 위해서다.
     *
     * 왜 null을 반환하나?
     * -> "아직 매칭 가능한 인원 수가 안 됨"을 서비스가 쉽게 판단하도록 하기 위함이다.
     *
     * 왜 중간 실패 시 즉시 롤백하나?
     * -> count명 추출이 완전히 성공하지 못했다면
     *    큐 상태를 원래대로 되돌려야 일관성이 유지되기 때문이다.
     */
    @Override
    public List<WaitingUser> pollMatchCandidates(QueueKey queueKey, int count) {
        Deque<WaitingUser> queue = waitingQueues.get(queueKey);

        if (queue == null) {
            return null;
        }

        synchronized (queue) {
            if (queue.size() < count) {
                return null;
            }

            List<WaitingUser> matchedUsers = new ArrayList<>(count);

            for (int i = 0; i < count; i++) {
                WaitingUser user = queue.pollFirst();
                if (user != null) {
                    matchedUsers.add(user);
                }
            }

            // poll 중 예상보다 적게 빠졌다면 즉시 롤백
            if (matchedUsers.size() < count) {
                for (int i = matchedUsers.size() - 1; i >= 0; i--) {
                    queue.addFirst(matchedUsers.get(i));
                }
                return null;
            }

            return matchedUsers;
        }
    }

    /**
     * 방 생성 실패 시 poll했던 유저들을 다시 큐 앞으로 되돌린다.
     *
     * 왜 앞으로 되돌리나?
     * -> 원래 pollFirst로 앞에서 꺼냈기 때문에
     *    실패했다면 기존 대기 순서를 최대한 그대로 복구해야 하기 때문이다.
     *
     * 왜 역순으로 addFirst 하나?
     * -> pollFirst로 꺼낸 순서를 유지하면서 원래 상태를 복원하려면
     *    마지막 유저부터 addFirst 해야 최종 순서가 맞는다.
     *
     * 예:
     * 꺼낸 순서가 [u1, u2, u3, u4] 라면
     * 복구는 u4, u3, u2, u1 순으로 addFirst 해야
     * 최종 큐가 [u1, u2, u3, u4, ...] 형태가 된다.
     */
    @Override
    public void rollbackPolledUsers(QueueKey queueKey, List<WaitingUser> users) {
        if (users == null || users.isEmpty()) {
            return;
        }

        Deque<WaitingUser> queue = waitingQueues.computeIfAbsent(queueKey, key -> new ConcurrentLinkedDeque<>());

        synchronized (queue) {
            // pollFirst로 꺼낸 순서를 유지하려면 역순으로 addFirst
            for (int i = users.size() - 1; i >= 0; i--) {
                queue.addFirst(users.get(i));
            }
        }
    }

    /**
     * 방 생성 성공 시 유저들을 SEARCHING -> MATCHED 상태로 전환한다.
     *
     * 동작 순서:
     * 1. userQueueMap에서 제거
     *    -> 더 이상 대기열 참가 상태가 아님
     * 2. MatchSession을 생성하고 각 유저를 해당 matchId에 연결
     *    -> /matches/me 조회 시 userId -> matchId -> MatchSession 순서로 MATCHED + roomId 응답 가능
     * 3. 해당 큐가 비었으면 waitingQueues에서도 제거
     *
     * 즉, 이 시점부터 유저는
     * "대기열에 있는 사용자"가 아니라
     * "어느 매치 그룹에 속해 있고, 그 결과로 입장해야 할 방이 정해진 사용자"가 된다.
     */
    @Override
    public void markMatched(QueueKey queueKey, List<WaitingUser> matchedUsers, Long roomId) {
        Long matchId = matchSequence.getAndIncrement();

        List<Long> participantIds =
                matchedUsers.stream().map(WaitingUser::getUserId).toList();

        MatchSession matchSession = MatchSession.matched(matchId, participantIds, roomId);

        // 세션 본문을 먼저 저장해두면, 이후 userMatchMap 연결 시점에
        // /matches/me 조회가 들어와도 matchId -> session 조회가 가능하다.
        matchSessionMap.put(matchId, matchSession);

        matchedUsers.forEach(user -> {
            userQueueMap.remove(user.getUserId());
            userMatchMap.put(user.getUserId(), matchId);
        });

        Deque<WaitingUser> queue = waitingQueues.get(queueKey);

        if (queue == null) {
            return;
        }

        synchronized (queue) {
            if (queue.isEmpty()) {
                waitingQueues.remove(queueKey, queue);
            }
        }
    }

    /**
     * 현재 해당 큐의 대기 인원 수를 반환한다.
     *
     * queue.size() 조회도 복합 로직과 섞일 수 있으므로
     * 큐 락 안에서 읽어서 일관성을 최대한 맞춘다.
     */
    @Override
    public int getWaitingCount(QueueKey queueKey) {
        Deque<WaitingUser> queue = waitingQueues.get(queueKey);

        if (queue == null) {
            return 0;
        }

        synchronized (queue) {
            return queue.size();
        }
    }

    /**
     * /queue/me 응답용 상태 조회
     *
     * 반환 의미:
     * - isInQueue = true  -> 현재 SEARCHING 상태로 큐에 들어가 있음
     * - isInQueue = false -> 큐에 들어가 있지 않음
     *
     * 왜 userQueueMap과 waitingQueues를 둘 다 확인하나?
     * -> userQueueMap만 믿으면 논리상 큐 참가 중처럼 보일 수 있지만,
     *    실제 큐 객체가 없을 수도 있으므로 최종적으로 실제 큐 존재 여부도 확인한다.
     *
     * 현재 정책:
     * - userQueueMap에는 있는데 실제 queue가 없으면
     *   조회 시 false 처리하고 stale 데이터 정리
     */
    @Override
    public QueueStateResponse getQueueState(Long userId) {
        QueueKey queueKey = userQueueMap.get(userId);

        if (queueKey == null) {
            return new QueueStateResponse(false, null, null, 0);
        }

        Deque<WaitingUser> queue = waitingQueues.get(queueKey);

        // 기존 동작 유지:
        // userQueueMap에는 있는데 실제 queue가 없으면 조회 시 false 처리하고 정리
        if (queue == null) {
            userQueueMap.remove(userId, queueKey);
            return new QueueStateResponse(false, null, null, 0);
        }

        synchronized (queue) {
            return new QueueStateResponse(
                    true, queueKey.category(), queueKey.difficulty().name(), queue.size());
        }
    }

    /**
     * /matches/me 응답용 상태 조회
     *
     * 우선순위:
     * 1. userMatchMap에 연결된 matchId가 있으면 MatchSession 조회
     * 2. session이 살아 있고 MATCHED 상태면 MATCHED
     * 3. userMatchMap은 있는데 session이 없으면 stale 상태로 보고 정리
     * 4. userQueueMap에 있으면 SEARCHING
     * 5. 둘 다 없으면 IDLE
     *
     * 즉 상태 해석은 다음과 같다.
     * - MATCHED   : 방이 잡혔고 roomId가 있음
     * - SEARCHING : 아직 큐에서 매칭 대기 중
     * - IDLE      : 대기 중도 아니고 매칭 완료 상태도 아님
     */
    @Override
    public MatchStateResponse getMatchState(Long userId) {
        Long matchId = userMatchMap.get(userId);

        if (matchId != null) {
            MatchSession matchSession = matchSessionMap.get(matchId);

            if (matchSession != null && matchSession.status() == MatchSessionStatus.MATCHED) {
                return new MatchStateResponse("MATCHED", matchSession.roomId());
            }

            // userMatchMap에는 연결이 남아 있는데 실제 세션이 없으면
            // 더 이상 유효한 매칭 상태가 아니므로 조회 시점에 정리한다.
            cleanupStaleUserMatch(userId, matchId);
        }

        if (userQueueMap.containsKey(userId)) {
            return new MatchStateResponse("SEARCHING", null);
        }

        return new MatchStateResponse("IDLE", null);
    }

    /**
     * 방 입장 성공 후 matched 상태를 정리한다.
     *
     * 지금 단계에서는 "방 입장을 끝낸 사용자"만 userMatchMap에서 분리하고,
     * 같은 matchId를 참조하는 사용자가 더 이상 없을 때만 MatchSession 본문도 제거한다.
     *
     * roomId를 함께 검증하는 이유:
     * 잘못된 roomId 요청으로 다른 매치 연결이 지워지지 않도록 하기 위해서다.
     */
    @Override
    public void clearMatchedRoom(Long userId, Long roomId) {
        if (roomId == null) {
            return;
        }

        Long matchId = userMatchMap.get(userId);

        if (matchId == null) {
            return;
        }

        MatchSession matchSession = matchSessionMap.get(matchId);

        if (matchSession == null) {
            userMatchMap.remove(userId, matchId);
            return;
        }

        if (!roomId.equals(matchSession.roomId())) {
            return;
        }

        userMatchMap.remove(userId, matchId);
        removeMatchSessionIfUnreferenced(matchId);
    }

    /**
     * 테스트 호환용 보조 메서드
     *
     * 현재 해당 queueKey에 대한 큐 객체가 존재하는지만 확인한다.
     * 실서비스 핵심 로직보다는 테스트/검증 성격에 가깝다.
     */
    @Override
    public boolean hasQueue(QueueKey queueKey) {
        return waitingQueues.containsKey(queueKey);
    }

    /**
     * userMatchMap에는 연결이 남아 있지만 실제 MatchSession이 없을 때
     * 조회 시점에 해당 사용자의 stale 연결을 정리한다.
     */
    private void cleanupStaleUserMatch(Long userId, Long matchId) {
        userMatchMap.remove(userId, matchId);
        removeMatchSessionIfUnreferenced(matchId);
    }

    /**
     * 같은 matchId를 참조하는 사용자가 더 이상 없으면
     * MatchSession 본문도 함께 제거한다.
     *
     * 현재는 한 매치 최대 인원이 작고(4명), 인메모리 MVP 단계라
     * containsValue 기반 선형 확인으로도 충분하다.
     * 이후 Redis 단계에서는 더 적합한 카운트/집합 구조로 바꿀 수 있다.
     */
    private void removeMatchSessionIfUnreferenced(Long matchId) {
        if (!userMatchMap.containsValue(matchId)) {
            matchSessionMap.remove(matchId);
        }
    }
}
