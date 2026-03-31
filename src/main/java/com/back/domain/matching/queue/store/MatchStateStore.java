package com.back.domain.matching.queue.store;

import java.time.LocalDateTime;
import java.util.List;

import com.back.domain.matching.queue.dto.QueueStateV2Response;
import com.back.domain.matching.queue.model.MatchSession;
import com.back.domain.matching.queue.model.QueueKey;
import com.back.domain.matching.queue.model.WaitingUser;

/**
 * 매칭 상태 저장소 추상화
 *
 * 지금은 인메모리 구현체를 사용하지만,
 * 이후 Redis 등으로 교체할 수 있도록 저장 경계를 먼저 만든다.
 *
 * 이번 단계부터는 v2 ready-check 흐름만 이 저장소를 사용하므로,
 * store는 매칭 상태 원본 조작에만 집중하고
 * 외부 응답 계약은 컨트롤러/서비스에서 해석한다.
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
    int enqueue(Long userId, String nickname, QueueKey queueKey);

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
     * v2에서는 4명 매칭 직후 room을 만들지 않고,
     * 먼저 ACCEPT_PENDING 세션을 생성해 ready-check를 시작한다.
     */
    MatchSession markAcceptPending(QueueKey queueKey, List<WaitingUser> matchedUsers, LocalDateTime deadline);

    /**
     * 특정 참가자의 decision을 ACCEPTED로 바꾼다.
     */
    // 전원 수락 판단과 room 생성은 서비스 책임이라서 여기서는 상태 변경만 한다.
    MatchSession accept(Long matchId, Long userId);

    /**
     * 특정 참가자의 decision을 DECLINED로 바꾸고 세션 종료 흐름을 시작한다.
     */
    MatchSession decline(Long matchId, Long userId);

    /**
     * 전원 수락 후 roomId를 세션에 연결하고 ROOM_READY 상태로 전환한다.
     */
    MatchSession markRoomReady(Long matchId, Long roomId);

    /**
     * deadline이 지난 ready-check 세션을 EXPIRED 상태로 전환한다.
     */
    MatchSession expire(Long matchId);

    /**
     * 거절 또는 방 생성 실패 등으로 세션을 CANCELLED 상태로 전환한다.
     */
    MatchSession cancelMatch(Long matchId);

    /**
     * 현재 해당 큐의 대기 인원 수를 반환한다.
     */
    int getWaitingCount(QueueKey queueKey);

    /**
     * v2 queue/me는 SEARCHING UI 전용이므로 requiredCount까지 함께 내려준다.
     */
    QueueStateV2Response getQueueStateV2(Long userId);

    /**
     * v2 matches/me는 서비스 계층에서 닉네임 같은 부가 정보를 조합해야 하므로,
     * 저장소는 세션 원본만 찾아서 돌려준다.
     */
    // store는 세션 원본 조회까지만 담당하고,
    // 닉네임 같은 화면용 정보 조립은 서비스가 맡는다.
    MatchSession findMatchSessionByUserId(Long userId);

    /**
     * 방 입장 성공 후 room-ready 세션 연결을 정리한다.
     */
    void clearMatchedRoom(Long userId, Long roomId);
}
