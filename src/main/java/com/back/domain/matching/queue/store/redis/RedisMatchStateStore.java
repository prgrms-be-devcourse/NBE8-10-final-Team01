package com.back.domain.matching.queue.store.redis;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;

import com.back.domain.matching.queue.dto.QueueStateV2Response;
import com.back.domain.matching.queue.model.MatchSession;
import com.back.domain.matching.queue.model.QueueKey;
import com.back.domain.matching.queue.model.WaitingUser;
import com.back.domain.matching.queue.store.MatchStateStore;
import com.back.domain.matching.queue.store.MatchingStoreProperties;

/**
 * Redis 기반 matching 저장소 스켈레톤이다.
 *
 * 이번 단계에서는 key 규칙, 직렬화 방식, 의존성 구조만 먼저 고정하고
 * 실제 queue / ready-check 상태 전이는 다음 하위 이슈에서 채운다.
 *
 * 아직 런타임 빈으로 연결하지 않았기 때문에 기존 동작에는 영향이 없다.
 */
public class RedisMatchStateStore implements MatchStateStore {

    private final StringRedisTemplate redisTemplate;
    private final MatchingRedisSerializer serializer;
    private final MatchingStoreProperties storeProperties;

    public RedisMatchStateStore(
            StringRedisTemplate redisTemplate,
            MatchingRedisSerializer serializer,
            MatchingStoreProperties storeProperties) {
        this.redisTemplate = redisTemplate;
        this.serializer = serializer;
        this.storeProperties = storeProperties;
    }

    @Override
    public int enqueue(Long userId, String nickname, QueueKey queueKey) {
        throw unsupported("enqueue");
    }

    @Override
    public CancelResult cancel(Long userId) {
        throw unsupported("cancel");
    }

    @Override
    public List<WaitingUser> pollMatchCandidates(QueueKey queueKey, int count) {
        throw unsupported("pollMatchCandidates");
    }

    @Override
    public void rollbackPolledUsers(QueueKey queueKey, List<WaitingUser> users) {
        throw unsupported("rollbackPolledUsers");
    }

    @Override
    public MatchSession markAcceptPending(QueueKey queueKey, List<WaitingUser> matchedUsers, LocalDateTime deadline) {
        throw unsupported("markAcceptPending");
    }

    @Override
    public MatchSession accept(Long matchId, Long userId) {
        throw unsupported("accept");
    }

    @Override
    public MatchSession decline(Long matchId, Long userId) {
        throw unsupported("decline");
    }

    @Override
    public RoomCreationAttempt tryBeginRoomCreation(Long matchId) {
        throw unsupported("tryBeginRoomCreation");
    }

    @Override
    public MatchSession markRoomReady(Long matchId, Long roomId) {
        throw unsupported("markRoomReady");
    }

    @Override
    public MatchSession expire(Long matchId) {
        throw unsupported("expire");
    }

    @Override
    public MatchSession cancelMatch(Long matchId) {
        throw unsupported("cancelMatch");
    }

    @Override
    public int getWaitingCount(QueueKey queueKey) {
        throw unsupported("getWaitingCount");
    }

    @Override
    public QueueStateV2Response getQueueStateV2(Long userId) {
        throw unsupported("getQueueStateV2");
    }

    @Override
    public MatchSession findMatchSessionByUserId(Long userId) {
        throw unsupported("findMatchSessionByUserId");
    }

    @Override
    public List<Long> findExpiredAcceptPendingMatchIds(LocalDateTime now) {
        throw unsupported("findExpiredAcceptPendingMatchIds");
    }

    @Override
    public void clearTerminalMatch(Long matchId) {
        throw unsupported("clearTerminalMatch");
    }

    @Override
    public void clearMatchedRoom(Long userId, Long roomId) {
        throw unsupported("clearMatchedRoom");
    }

    /**
     * 다음 하위 이슈에서 실제 전환을 시작할 때 바로 사용할 기본 의존성 접근점이다.
     */
    StringRedisTemplate redisTemplate() {
        return redisTemplate;
    }

    /**
     * 직렬화 방식은 StringRedisTemplate + JSON 조합으로 고정한다.
     */
    MatchingRedisSerializer serializer() {
        return serializer;
    }

    /**
     * 설정 구조는 미리 받아 두되, 이번 단계에서는 동작 분기에 연결하지 않는다.
     */
    MatchingStoreProperties storeProperties() {
        return storeProperties;
    }

    private UnsupportedOperationException unsupported(String methodName) {
        return new UnsupportedOperationException("RedisMatchStateStore." + methodName + " 는 다음 하위 이슈에서 구현합니다.");
    }
}
