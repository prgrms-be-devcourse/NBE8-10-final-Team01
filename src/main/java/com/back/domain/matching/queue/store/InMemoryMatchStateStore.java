package com.back.domain.matching.queue.store;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.back.domain.matching.queue.dto.QueueStateV2Response;
import com.back.domain.matching.queue.model.MatchSession;
import com.back.domain.matching.queue.model.MatchSessionStatus;
import com.back.domain.matching.queue.model.QueueKey;
import com.back.domain.matching.queue.model.ReadyDecision;
import com.back.domain.matching.queue.model.WaitingUser;
import com.back.global.exception.ServiceException;

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
 * matching 서비스들은 이 저장소를 통해서만 상태를 다루게 만든다.
 *
 * 즉,
 * 서비스는 "큐에 참가", "취소", "매칭 후보 추출", "매칭 완료 처리" 같은 흐름만 담당하고
 * 실제 자료구조(Map, Deque) 조작은 이 저장소가 담당한다.
 *
 * 지금은 인메모리 기반이지만,
 * 이후 Redis 등 외부 저장소로 바꾸더라도 서비스 로직 변경을 최소화하기 위해
 * 저장 경계를 먼저 분리한 구조다.
 *
 * 이번 단계에서는 같은 저장소 안에 v2 ready-check 상태도 함께 저장한다.
 * 다만 v1과 v2가 같은 상태를 어떻게 해석할지는 서비스/DTO 계층에서 분리한다.
 */
@Component
public class InMemoryMatchStateStore implements MatchStateStore {

    private static final int REQUIRED_MATCH_SIZE = 4;

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
     * 현재 v2에서는 participantDecisions와 deadline도 이 본문 안에 같이 저장한다.
     * 즉 matchSessionMap이 ready-check 세션의 단일 원본이다.
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
    public int enqueue(Long userId, String nickname, QueueKey queueKey) {
        // join ??? queue ?? ??? ? ? ???, ?? match session ??? ?? ???? ??.
        // ?: CANCELLED ??? ? user1? ?? join? ??? old match ??? ???? ? queue ??? ????.
        // active session?? ?join? ??, terminal/stale session?? ?? ??? ??? ??? ? ????.
        ensureJoinEligibility(userId);
        if (userQueueMap.putIfAbsent(userId, queueKey) != null) {
            throw new ServiceException("409-1", "이미 매칭 대기열에 참가 중인 사용자입니다.");
        }

        Deque<WaitingUser> queue = waitingQueues.computeIfAbsent(queueKey, key -> new ConcurrentLinkedDeque<>());

        synchronized (queue) {
            queue.addLast(new WaitingUser(userId, nickname, queueKey));
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
     * v2 ready-check 시작 시 유저들을 SEARCHING -> ACCEPT_PENDING 상태로 전환한다.
     *
     * 여기서는 roomId를 만들지 않고 세션만 만든다.
     * 왜냐하면 ready-check에서는 "4명이 모였다"와 "전원 수락해 방이 준비됐다"를
     * 다른 상태로 분리해야 하기 때문이다.
     */
    @Override
    public MatchSession markAcceptPending(QueueKey queueKey, List<WaitingUser> matchedUsers, LocalDateTime deadline) {
        Long matchId = matchSequence.getAndIncrement();
        // WaitingUser는 queue에 묶인 임시 객체이고,
        // 세션 본문에는 결국 userId 목록만 남기면 되므로 여기서 변환한다.
        List<Long> participantIds =
                matchedUsers.stream().map(WaitingUser::getUserId).toList();
        // participantIds 는 기존처럼 room 생성 요청과 응답 순서를 고정하는 용도로 유지한다.
        // nickname snapshot 은 같은 userId 키로 묶어서 matches/me 가 DB 조회 없이 참가자 목록을 조립하게 한다.
        // 예: participantIds = [1,2,3,4], participantNicknames = {1:"m1", 2:"m2", 3:"m3", 4:"m4"}
        Map<Long, String> participantNicknames = new LinkedHashMap<>();
        matchedUsers.forEach(user -> participantNicknames.put(user.getUserId(), user.getNickname()));

        MatchSession matchSession =
                MatchSession.acceptPending(matchId, queueKey, participantIds, participantNicknames, deadline);

        // 세션 본문을 먼저 저장한 뒤
        // user -> match 연결을 만든다.
        matchSessionMap.put(matchId, matchSession);

        matchedUsers.forEach(user -> {
            // ready-check로 넘어간 사용자는 더 이상 SEARCHING queue 참가자가 아니다.
            // 큐 맵에서 지워서 false로 되면 폴링중단
            userQueueMap.remove(user.getUserId());
            userMatchMap.put(user.getUserId(), matchId);
        });

        cleanupEmptyQueue(queueKey);
        return matchSession;
    }

    /**
     * 특정 참가자의 decision을 ACCEPTED로 바꾼다.
     *
     * 왜 여기서는 decision만 바꾸나?
     * -> 전원 수락 여부 판단과 room 생성은 저장소 책임이 아니라
     *    ReadyCheckService의 흐름 제어 책임으로 두기 위해서다.
     */
    @Override
    public MatchSession accept(Long matchId, Long userId) {
        final LocalDateTime now = LocalDateTime.now();
        final MatchSession[] resultHolder = new MatchSession[1];

        matchSessionMap.compute(matchId, (id, currentSession) -> {
            if (currentSession == null) {
                throw new IllegalStateException("존재하지 않는 매치 세션입니다.");
            }

            if (!currentSession.hasParticipant(userId)) {
                throw new IllegalStateException("매치 참가자가 아닌 사용자는 수락할 수 없습니다.");
            }

            if (currentSession.isExpiredAt(now)) {
                // 스케줄러 대신 lazy expire를 사용하므로,
                // 읽기/액션 시점에 deadline을 넘겼으면 여기서 바로 EXPIRED로 바꾼다.
                MatchSession expiredSession = currentSession.status() == MatchSessionStatus.ACCEPT_PENDING
                        ? currentSession.expired()
                        : currentSession;
                resultHolder[0] = expiredSession;
                return expiredSession;
            }

            if (currentSession.status() != MatchSessionStatus.ACCEPT_PENDING) {
                // 이미 ROOM_CREATING, ROOM_READY, CANCELLED, EXPIRED 같은 상태면 더 바꾸지 않는다.
                resultHolder[0] = currentSession;
                return currentSession;
            }

            if (currentSession.decisionOf(userId) == ReadyDecision.ACCEPTED) {
                // 같은 사용자의 중복 accept는 현재 상태를 그대로 돌려준다.
                resultHolder[0] = currentSession;
                return currentSession;
            }

            MatchSession updatedSession = currentSession.withDecision(userId, ReadyDecision.ACCEPTED);
            resultHolder[0] = updatedSession;
            return updatedSession;
        });

        return resultHolder[0];
    }

    /**
     * 특정 참가자의 decision을 DECLINED로 바꾸고 세션 전체를 취소 상태로 전환한다.
     *
     * ready-check에서 한 명이라도 거절하면 더 이상 같은 세션을 유지할 의미가 없으므로
     * 세션 전체를 CANCELLED로 처리한다.
     */
    @Override
    public MatchSession decline(Long matchId, Long userId) {
        final LocalDateTime now = LocalDateTime.now();
        final MatchSession[] resultHolder = new MatchSession[1];

        matchSessionMap.compute(matchId, (id, currentSession) -> {
            if (currentSession == null) {
                throw new IllegalStateException("존재하지 않는 매치 세션입니다.");
            }

            if (!currentSession.hasParticipant(userId)) {
                throw new IllegalStateException("매치 참가자가 아닌 사용자는 거절할 수 없습니다.");
            }

            if (currentSession.isExpiredAt(now)) {
                // 거절을 누른 시점에 이미 deadline이 지났다면 거절보다 만료를 우선 반영한다.
                MatchSession expiredSession = currentSession.status() == MatchSessionStatus.ACCEPT_PENDING
                        ? currentSession.expired()
                        : currentSession;
                resultHolder[0] = expiredSession;
                return expiredSession;
            }

            if (currentSession.status() != MatchSessionStatus.ACCEPT_PENDING) {
                resultHolder[0] = currentSession;
                return currentSession;
            }

            // 이번 단계 정책에서는 한 명이 DECLINED가 되면 세션 전체를 종료한다.
            MatchSession updatedSession =
                    currentSession.withDecision(userId, ReadyDecision.DECLINED).cancelled();
            resultHolder[0] = updatedSession;
            return updatedSession;
        });

        return resultHolder[0];
    }

    @Override
    public RoomCreationAttempt tryBeginRoomCreation(Long matchId) {
        final boolean[] acquired = new boolean[] {false};
        final MatchSession[] resultHolder = new MatchSession[1];

        matchSessionMap.compute(matchId, (id, currentSession) -> {
            if (currentSession == null) {
                throw new IllegalStateException("존재하지 않는 매치 세션입니다.");
            }

            if (currentSession.status() != MatchSessionStatus.ACCEPT_PENDING) {
                resultHolder[0] = currentSession;
                return currentSession;
            }

            if (!currentSession.isAllAccepted()) {
                resultHolder[0] = currentSession;
                return currentSession;
            }

            // 마지막 accept 경쟁 상황에서는 한 요청만 ROOM_CREATING으로 선점해야
            // createRoom(...)가 한 번만 호출된다.
            MatchSession roomCreatingSession = currentSession.roomCreating();
            acquired[0] = true;
            resultHolder[0] = roomCreatingSession;
            return roomCreatingSession;
        });

        return new RoomCreationAttempt(resultHolder[0], acquired[0]);
    }

    /**
     * 전원 수락이 끝난 뒤 roomId를 세션에 연결한다.
     *
     * 이 메서드를 별도로 둔 이유는
     * ACCEPT_PENDING과 ROOM_READY를 명확히 나누기 위해서다.
     */
    @Override
    public MatchSession markRoomReady(Long matchId, Long roomId) {
        final MatchSession[] resultHolder = new MatchSession[1];

        matchSessionMap.compute(matchId, (id, currentSession) -> {
            if (currentSession == null) {
                throw new IllegalStateException("존재하지 않는 매치 세션입니다.");
            }

            if (currentSession.status() == MatchSessionStatus.ROOM_READY) {
                resultHolder[0] = currentSession;
                return currentSession;
            }

            if (currentSession.status() != MatchSessionStatus.ROOM_CREATING
                    && currentSession.status() != MatchSessionStatus.ACCEPT_PENDING) {
                resultHolder[0] = currentSession;
                return currentSession;
            }

            // roomId가 세션에 연결되는 순간부터 프론트는 실제 입장 가능한 상태가 된다.
            MatchSession updatedSession = currentSession.roomReady(roomId);
            resultHolder[0] = updatedSession;
            return updatedSession;
        });

        return resultHolder[0];
    }

    /**
     * deadline이 지난 ACCEPT_PENDING 세션을 EXPIRED로 바꾼다.
     *
     * 이미 최종 상태로 넘어간 세션은 그대로 두고,
     * 수락 대기 중인 세션에만 만료를 적용한다.
     */
    @Override
    public MatchSession expire(Long matchId) {
        MatchSession matchSession = requireMatchSession(matchId);

        if (matchSession.status() != MatchSessionStatus.ACCEPT_PENDING) {
            // 이미 최종 상태로 바뀐 세션은 중복 만료 처리하지 않는다.
            return matchSession;
        }

        MatchSession updatedSession = matchSession.expired();
        matchSessionMap.put(matchId, updatedSession);
        return updatedSession;
    }

    /**
     * 거절 또는 방 생성 실패 같은 이유로 세션 전체를 취소 상태로 바꾼다.
     */
    @Override
    public MatchSession cancelMatch(Long matchId) {
        final MatchSession[] resultHolder = new MatchSession[1];

        matchSessionMap.compute(matchId, (id, currentSession) -> {
            if (currentSession == null) {
                throw new IllegalStateException("존재하지 않는 매치 세션입니다.");
            }

            if (currentSession.status() == MatchSessionStatus.CANCELLED) {
                resultHolder[0] = currentSession;
                return currentSession;
            }

            // room 생성 실패 같은 시스템 사유도 ready-check 거절과 동일하게 취소 상태로 묶는다.
            MatchSession updatedSession = currentSession.cancelled();
            resultHolder[0] = updatedSession;
            return updatedSession;
        });

        return resultHolder[0];
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

    /**
     * /queue/me 응답용 상태 조회
     *
     * v2에서는 queue/me를 SEARCHING UI 전용으로 유지하므로,
     * waitingCount와 함께 requiredCount도 내려준다.
     */
    @Override
    public QueueStateV2Response getQueueStateV2(Long userId) {
        QueueKey queueKey = userQueueMap.get(userId);

        if (queueKey == null) {
            // userQueueMap에 연결이 없다는 뜻은 이미 queue 단계가 끝났다는 뜻이다.
            return new QueueStateV2Response(false, null, null, 0, REQUIRED_MATCH_SIZE);
        }

        Deque<WaitingUser> queue = waitingQueues.get(queueKey);

        if (queue == null) {
            userQueueMap.remove(userId, queueKey);
            return new QueueStateV2Response(false, null, null, 0, REQUIRED_MATCH_SIZE);
        }

        synchronized (queue) {
            // requiredCount를 함께 내려 프론트가 1/4, 2/4 UI를 바로 만들 수 있게 한다.
            return new QueueStateV2Response(
                    true, queueKey.category(), queueKey.difficulty().name(), queue.size(), REQUIRED_MATCH_SIZE);
        }
    }

    /**
     * v2 matches/me 응답 조립을 위한 세션 원본 조회
     *
     * 왜 DTO를 여기서 바로 만들지 않나?
     * -> participant nickname 같은 화면용 정보는 store 책임이 아니라
     *    서비스 계층에서 회원 정보를 합쳐 만드는 쪽이 더 자연스럽기 때문이다.
     */
    @Override
    public MatchSession findMatchSessionByUserId(Long userId) {
        Long matchId = userMatchMap.get(userId);

        if (matchId == null) {
            // queue에서도 빠졌고 match 연결도 없다면 ready-check 대상이 아니다.
            return null;
        }

        MatchSession matchSession = matchSessionMap.get(matchId);

        if (matchSession == null) {
            // user -> match 연결은 남아 있는데 본문이 사라진 경우 조회 시점에 정리한다.
            cleanupStaleUserMatch(userId, matchId);
            return null;
        }

        if (matchSession.status() == MatchSessionStatus.CLOSED) {
            cleanupStaleUserMatch(userId, matchId);
            return null;
        }

        if (matchSession.status() == MatchSessionStatus.CANCELLED
                || matchSession.status() == MatchSessionStatus.EXPIRED) {
            // 즉시 정리 대상 terminal 세션은 조회 복구 대상으로 남기지 않는다.
            clearTerminalMatch(matchId);
            return null;
        }

        return matchSession;
    }

    @Override
    public List<Long> findExpiredAcceptPendingMatchIds(LocalDateTime now) {
        return matchSessionMap.values().stream()
                .filter(matchSession -> matchSession.status() == MatchSessionStatus.ACCEPT_PENDING)
                .filter(matchSession -> !matchSession.deadline().isAfter(now))
                .map(MatchSession::matchId)
                .toList();
    }

    @Override
    public void clearTerminalMatch(Long matchId) {
        MatchSession removedSession = matchSessionMap.remove(matchId);

        if (removedSession != null) {
            // terminal 세션은 참가자 전체 연결을 한 번에 정리한다.
            removedSession.participantIds().forEach(participantId -> userMatchMap.remove(participantId, matchId));
            return;
        }

        userMatchMap.entrySet().removeIf(entry -> entry.getValue().equals(matchId));
    }

    /**
     * 방 입장 성공 후 room-ready 세션 연결을 정리한다.
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
            // 잘못된 roomId 요청으로 다른 세션 연결이 지워지지 않게 방어한다.
            return;
        }

        // 입장에 성공한 사용자만 세션 참조에서 제거한다.
        userMatchMap.remove(userId, matchId);
        // 유저 매치맵이 다 삭제되면 그 후에 매칭방삭제
        removeMatchSessionIfUnreferenced(matchId);
    }

    private MatchSession requireMatchSession(Long matchId) {
        MatchSession matchSession = matchSessionMap.get(matchId);

        if (matchSession == null) {
            throw new IllegalStateException("존재하지 않는 매치 세션입니다.");
        }

        return matchSession;
    }

    /**
     * queue 재진입 전 현재 사용자의 기존 match session 연결을 먼저 확인한다.
     *
     * active session은 보호해야 하므로 재join을 차단하고,
     * terminal/stale session만 현재 사용자 기준으로 정리한 뒤 새 queue 참가를 허용한다.
     */
    private void ensureJoinEligibility(Long userId) {
        Long matchId = userMatchMap.get(userId);

        if (matchId == null) {
            return;
        }

        MatchSession matchSession = matchSessionMap.get(matchId);

        if (matchSession == null) {
            // userMatchMap에는 연결이 남아 있지만 세션 본문이 이미 없는 경우다.
            // 이건 "종료 상태를 보여줄 세션"조차 없어진 stale link라서 현재 사용자 연결만 바로 정리한다.
            cleanupStaleUserMatch(userId, matchId);
            return;
        }

        switch (matchSession.status()) {
            case ACCEPT_PENDING, ROOM_READY -> throw new ServiceException("409-1", "이미 진행 중인 매칭이 있습니다.");
            case CANCELLED, EXPIRED, CLOSED -> {
                // 즉시 정리 정책을 쓰므로 남아 있는 terminal 세션은 재참가 전에 함께 치운다.
                clearTerminalMatch(matchId);
            }
        }
    }

    private void cleanupEmptyQueue(QueueKey queueKey) {
        Deque<WaitingUser> queue = waitingQueues.get(queueKey);

        if (queue == null) {
            return;
        }

        synchronized (queue) {
            if (queue.isEmpty()) {
                // 비어 있는 queue 객체를 제거해 queue/me 조회와 테스트 상태를 깔끔하게 유지한다.
                waitingQueues.remove(queueKey, queue);
            }
        }
    }

    /**
     * userMatchMap에는 연결이 남아 있지만 실제 MatchSession이 없을 때
     * 조회 시점에 해당 사용자의 stale 연결을 정리한다.
     */
    private void cleanupStaleUserMatch(Long userId, Long matchId) {
        // 본문이 사라진 matchId를 계속 들고 있으면 이후 조회가 꼬이므로 즉시 정리한다.
        // 여기서는 이미 세션 본문이 없기 때문에 "누구에게 종료 상태를 보여줄지"를 고려할 필요가 없다.
        userMatchMap.remove(userId, matchId);
        removeMatchSessionIfUnreferenced(matchId);
    }

    /**
     * terminal session은 matches/me에서 계속 보여줘야 하므로 세션 전체를 즉시 지우지 않는다.
     * 다시 join을 시도한 현재 사용자만 기존 match 연결에서 분리하고,
     * 마지막 참조자까지 빠졌을 때만 세션 본문이 제거되게 한다.
     */
    private void cleanupTerminalUserMatch(Long userId, Long matchId) {
        // terminal session은 본문이 살아 있으므로 종료 모달 용도로 아직 의미가 있다.
        // 그래서 join을 다시 누른 현재 사용자만 old match에서 분리하고, 다른 참가자 링크는 그대로 둔다.
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
        // matchmap 에
        // 1 -> 10
        // 2 -> 10
        // 가르키고 있으면 삭제하지말아라
        if (!userMatchMap.containsValue(matchId)) {
            // 더 이상 이 matchId를 참조하는 사용자가 없을 때만 세션 본문을 제거한다.
            matchSessionMap.remove(matchId);
        }
    }
}
