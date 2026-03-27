package com.back.domain.matching.queue.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.back.domain.battle.battleroom.dto.CreateRoomRequest;
import com.back.domain.battle.battleroom.dto.CreateRoomResponse;
import com.back.domain.battle.battleroom.service.BattleRoomService;
import com.back.domain.matching.queue.adapter.QueueProblemPicker;
import com.back.domain.matching.queue.dto.MatchStateResponse;
import com.back.domain.matching.queue.dto.QueueJoinRequest;
import com.back.domain.matching.queue.dto.QueueStateResponse;
import com.back.domain.matching.queue.dto.QueueStatusResponse;
import com.back.domain.matching.queue.model.QueueKey;
import com.back.domain.matching.queue.model.WaitingUser;
import com.back.domain.matching.queue.store.MatchStateStore;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MatchingQueueService {

    // TODO: 지금 현재 인메모리 방식임 redis로 전환하면 좋음 MVP 이기 때문

    // 매칭 성사 시 방 생성 호출용 서비스
    private final BattleRoomService battleRoomService;

    private final QueueProblemPicker queueProblemPicker;

    // 인메모리 상태 저장 책임을 별도 store로 분리
    private final MatchStateStore matchStateStore;

    public QueueStatusResponse joinQueue(Long userId, QueueJoinRequest request) {
        // 이미 대기열에 들어가 있는 유저는 다시 참가할 수 없다.
        QueueKey queueKey = new QueueKey(request.getCategory(), request.getDifficulty());

        // 큐 참가와 현재 대기 인원 계산
        int currentSize = matchStateStore.enqueue(userId, queueKey);

        // 1차 빠른 체크: 4명 미만이면 굳이 매칭 함수까지 안 들어감
        if (currentSize < 4) {
            return new QueueStatusResponse(
                    "매칭 대기열에 참가했습니다.",
                    queueKey.category(),
                    queueKey.difficulty().name(),
                    currentSize // \ queue.size() 대신 락 안에서 구한 currentSize 사용
                    );
        }

        // 4명 이상일 때만 실제 매칭 시도
        CreateRoomResponse room = tryMatchAndCreateRoom(queueKey);

        if (room != null) {
            return new QueueStatusResponse(
                    "매칭 성사 및 방 생성 완료 (roomId=" + room.roomId() + ")",
                    queueKey.category(),
                    queueKey.difficulty().name(),
                    0 //  매칭 성공이면 이 유저는 더 이상 대기열에 없으므로 0으로 명확화
                    );
        }

        // 아직 인원 부족이면 대기 상태 응답
        int remainingSize = matchStateStore.getWaitingCount(queueKey);

        return new QueueStatusResponse(
                "매칭 대기열에 참가했습니다.",
                queueKey.category(),
                queueKey.difficulty().name(),
                remainingSize // 락 밖 queue.size() 직접 호출 제거
                );
    }

    public QueueStatusResponse cancelQueue(Long userId) {
        // cancel 처리 결과는 store가 반환
        MatchStateStore.CancelResult cancelResult = matchStateStore.cancel(userId);
        QueueKey queueKey = cancelResult.queueKey();

        return new QueueStatusResponse(
                "매칭 대기열에서 취소했습니다.", queueKey.category(), queueKey.difficulty().name(), cancelResult.waitingCount());
    }

    // 4인 매칭 + 방 생성
    private CreateRoomResponse tryMatchAndCreateRoom(QueueKey queueKey) {

        List<WaitingUser> matchedUsers = matchStateStore.pollMatchCandidates(queueKey, 4);

        if (matchedUsers == null) {
            return null;
        }

        // 4) 방 생성 API에 넘길 참가자 ID 목록 생성
        List<Long> participantIds =
                matchedUsers.stream().map(WaitingUser::getUserId).toList();

        try {
            Long problemId = resolveProblemIdForMatch(queueKey, participantIds);

            CreateRoomResponse response =
                    battleRoomService.createRoom(new CreateRoomRequest(problemId, participantIds, 4));

            // EARCHING -> MATCHED 전환도 store가 담당
            matchStateStore.markMatched(queueKey, matchedUsers, response.roomId());

            return response;
        } catch (RuntimeException e) {
            matchStateStore.rollbackPolledUsers(queueKey, matchedUsers);
            throw e;
        }
    }

    public QueueStateResponse getMyQueueState(Long userId) {
        return matchStateStore.getQueueState(userId);
    }

    public MatchStateResponse getMyMatchState(Long userId) {
        return matchStateStore.getMatchState(userId);
    }

    public void clearMatchedRoom(Long userId, Long roomId) {
        matchStateStore.clearMatchedRoom(userId, roomId);
    }

    private Long resolveProblemIdForMatch(QueueKey queueKey, List<Long> participantIds) {
        return queueProblemPicker.pick(queueKey, participantIds);
    }

    boolean hasQueue(QueueKey queueKey) {
        return matchStateStore.hasQueue(queueKey);
    }
}
