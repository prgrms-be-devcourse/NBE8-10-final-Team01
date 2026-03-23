package com.back.domain.matching.queue.service;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.stereotype.Service;

import com.back.domain.battle.battleroom.dto.CreateRoomRequest;
import com.back.domain.battle.battleroom.dto.CreateRoomResponse;
import com.back.domain.battle.battleroom.service.BattleRoomService;
import com.back.domain.matching.queue.adapter.QueueProblemPicker;
import com.back.domain.matching.queue.dto.QueueJoinRequest;
import com.back.domain.matching.queue.dto.QueueStatusResponse;
import com.back.domain.matching.queue.model.QueueKey;
import com.back.domain.matching.queue.model.WaitingUser;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
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

    // 매칭 성사 시 방 생성 호출용 서비스
    private final BattleRoomService battleRoomService;

    private final QueueProblemPicker queueProblemPicker;

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
        int currentSize;
        synchronized (queue) {
            queue.addLast(waitingUser);
            currentSize = queue.size();
        }
        // 1차 빠른 체크: 4명 미만이면 굳이 매칭 함수까지 안 들어감
        if (currentSize < 4) {
            return new QueueStatusResponse(
                    "매칭 대기열에 참가했습니다.",
                    queueKey.category(),
                    queueKey.difficulty().name(),
                    queue.size());
        }

        // 4명 이상일 때만 실제 매칭 시도
        CreateRoomResponse room = tryMatchAndCreateRoom(queueKey, queue);

        if (room != null) {
            return new QueueStatusResponse(
                    "매칭 성사 및 방 생성 완료 (roomId=" + room.roomId() + ")",
                    queueKey.category(),
                    queueKey.difficulty().name(),
                    queue.size());
        }

        // 아직 인원 부족이면 대기 상태 응답
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

        int currentSize;
        boolean removed;

        synchronized (queue) {
            // 5. 큐에서 해당 userId를 가진 WaitingUser 제거
            removed = queue.removeIf(waitingUser -> waitingUser.getUserId().equals(userId));

            // 7. 큐에서 제거 실패 시 예외
            if (!removed) {
                throw new IllegalStateException("대기열에서 사용자를 제거하지 못했습니다.");
            }

            // 6. userQueueMap에서도 제거
            userQueueMap.remove(userId);
            currentSize = queue.size();
        }

        // 8. 해당 큐가 비어 있으면 삭제하고, 안 비어 있으면 그대로 둬라
        waitingQueues.computeIfPresent(queueKey, (key, q) -> q.isEmpty() ? null : q);

        // 9. 응답 반환
        return new QueueStatusResponse(
                "매칭 대기열에서 취소되었습니다.", queueKey.category(), queueKey.difficulty().name(), currentSize);
    }

    // 4인 매칭 + 방 생성
    private CreateRoomResponse tryMatchAndCreateRoom(QueueKey queueKey, Deque<WaitingUser> queue) {

        List<WaitingUser> matchedUsers;

        // 락 안에서는 4명 확인 + 4명 추출까지만 수행
        synchronized (queue) {
            // 2차 체크: 바깥에서 4명 이상이었더라도
            // 이 시점에는 다른 스레드가 먼저 가져갔을 수 있으므로 다시 확인 필요
            // 4명 미만이면 매칭 X
            if (queue.size() < 4) {
                return null;
            }

            // FIFO 순서대로 앞에서 4명 추출
            matchedUsers = new ArrayList<>(4);
            for (int i = 0; i < 4; i++) {
                WaitingUser user = queue.pollFirst();
                if (user != null) {
                    matchedUsers.add(user);
                }
            }

            // 비정상 안전장치: 추출 인원이 4명 미만이면 원상복구 후 중단
            if (matchedUsers.size() < 4) {
                for (int i = matchedUsers.size() - 1; i >= 0; i--) {
                    queue.addFirst(matchedUsers.get(i));
                }
                return null;
            }
        }

        // 4) 방 생성 API에 넘길 참가자 ID 목록 생성
        List<Long> participantIds =
                matchedUsers.stream().map(WaitingUser::getUserId).toList();

        try {
            // 5) 문제 번호는 외부(찬의님 파트) 연동 함수에서 결정
            Long problemId = resolveProblemIdForMatch(queueKey, participantIds);

            // 6) 배틀룸 생성 요청 (MVP: maxPlayers=4 고정)
            CreateRoomResponse response =
                    battleRoomService.createRoom(new CreateRoomRequest(problemId, participantIds, 4));

            // 7) 방 생성 성공 시에만 "유저-큐 맵"에서 매칭된 유저 제거
            //    (실패했는데 먼저 제거하면 상태 꼬임 발생)
            matchedUsers.forEach(user -> userQueueMap.remove(user.getUserId()));

            // 8) 이 큐가 비었으면 waitingQueues 맵에서 키 제거(메모리 정리)
            waitingQueues.computeIfPresent(queueKey, (k, q) -> q.isEmpty() ? null : q);

            return response;
        } catch (RuntimeException e) {
            // 생성 실패 시 큐 원복: 뽑았던 4명을 원래 순서대로 되돌리기
            // 롤백도 큐 변경이므로 같은 락으로 보호
            synchronized (queue) {
                for (int i = matchedUsers.size() - 1; i >= 0; i--) {
                    queue.addFirst(matchedUsers.get(i));
                }
            }
            throw e;
        }
    }

    private Long resolveProblemIdForMatch(QueueKey queueKey, List<Long> participantIds) {
        return queueProblemPicker.pick(queueKey, participantIds);
    }

    // 테스트에서 큐 정리 여부를 확인하기 위한 package-private 조회 메서드
    boolean hasQueue(QueueKey queueKey) {
        return waitingQueues.containsKey(queueKey);
    }
}
