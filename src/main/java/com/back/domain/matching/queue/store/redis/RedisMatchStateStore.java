package com.back.domain.matching.queue.store.redis;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.back.domain.matching.queue.dto.QueueStateV2Response;
import com.back.domain.matching.queue.model.MatchSession;
import com.back.domain.matching.queue.model.QueueKey;
import com.back.domain.matching.queue.model.WaitingUser;
import com.back.domain.matching.queue.store.InMemoryMatchStateStore;
import com.back.domain.matching.queue.store.MatchStateStore;
import com.back.domain.matching.queue.store.MatchingStoreProperties;
import com.back.global.exception.ServiceException;

/**
 * Redis 기반 matching 저장소다.
 *
 * 이번 단계에서는 SEARCHING 구간의 queue 상태만 Redis로 옮기고,
 * ready-check 세션 본문과 상태 전이는 기존 인메모리 구현에 위임한다.
 *
 * 즉 queue는 Redis, ready-check는 memory인 하이브리드 저장소이며
 * 다음 하위 이슈에서 ready-check 전이도 Redis로 옮길 수 있게 경계를 먼저 만든다.
 */
@Primary
@Component
@ConditionalOnProperty(prefix = "matching.store", name = "type", havingValue = "redis")
public class RedisMatchStateStore implements MatchStateStore {

    private static final int REQUIRED_MATCH_SIZE = 4;

    private static final RedisScript<Long> ENQUEUE_SCRIPT = longScript("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return -1
            end
            redis.call('SET', KEYS[1], ARGV[1])
            redis.call('RPUSH', KEYS[2], ARGV[1])
            return redis.call('LLEN', KEYS[2])
            """);

    private static final RedisScript<Long> QUEUE_CONTAINS_SCRIPT = longScript("""
            local pos = redis.call('LPOS', KEYS[1], ARGV[1])
            if pos then
                return 1
            end
            return 0
            """);

    private static final RedisScript<List> POLL_SCRIPT = listScript("""
            local count = tonumber(ARGV[1])
            if redis.call('LLEN', KEYS[1]) < count then
                return {}
            end

            local result = {}
            for i = 1, count do
                result[i] = redis.call('LPOP', KEYS[1])
            end

            if redis.call('LLEN', KEYS[1]) == 0 then
                redis.call('DEL', KEYS[1])
            end

            return result
            """);

    private static final RedisScript<List> CANCEL_SCRIPT = listScript("""
            local removed = redis.call('LREM', KEYS[2], 1, ARGV[1])
            redis.call('DEL', KEYS[1])
            local size = redis.call('LLEN', KEYS[2])
            if size == 0 then
                redis.call('DEL', KEYS[2])
            end
            return {removed, size}
            """);

    private static final RedisScript<Long> ROLLBACK_SCRIPT = longScript("""
            for i = #ARGV, 1, -1 do
                redis.call('LPUSH', KEYS[1], ARGV[i])
            end

            for i = 2, #KEYS do
                redis.call('SET', KEYS[i], ARGV[i - 1])
            end

            return redis.call('LLEN', KEYS[1])
            """);

    private final StringRedisTemplate redisTemplate;
    private final MatchingRedisSerializer serializer;
    private final MatchingStoreProperties storeProperties;
    private final InMemoryMatchStateStore delegate;

    public RedisMatchStateStore(
            StringRedisTemplate redisTemplate,
            MatchingRedisSerializer serializer,
            MatchingStoreProperties storeProperties,
            InMemoryMatchStateStore delegate) {
        this.redisTemplate = redisTemplate;
        this.serializer = serializer;
        this.storeProperties = storeProperties;
        this.delegate = delegate;
    }

    @Override
    public int enqueue(Long userId, String nickname, QueueKey queueKey) {
        ensureJoinEligibility(userId);

        if (findActiveQueuePayload(userId) != null) {
            throw new ServiceException("409-1", "이미 매칭 대기열에 참가 중인 사용자입니다.");
        }

        WaitingUser waitingUser = new WaitingUser(userId, nickname, queueKey);
        String payload = serializer.writeWaitingUser(waitingUser);

        Long currentSize = redisTemplate.execute(
                ENQUEUE_SCRIPT,
                List.of(MatchingRedisKeys.userQueue(userId), MatchingRedisKeys.queue(queueKey)),
                payload);

        if (currentSize == null) {
            throw new IllegalStateException("Redis queue enqueue 결과를 확인할 수 없습니다.");
        }

        if (currentSize == -1L) {
            throw new ServiceException("409-1", "이미 매칭 대기열에 참가 중인 사용자입니다.");
        }

        return currentSize.intValue();
    }

    @Override
    public CancelResult cancel(Long userId) {
        String userQueueKey = MatchingRedisKeys.userQueue(userId);
        String payload = redisTemplate.opsForValue().get(userQueueKey);

        if (payload == null) {
            throw new IllegalStateException("현재 매칭 대기열에 참가 중이 아닙니다.");
        }

        WaitingUser waitingUser = serializer.readWaitingUser(payload);
        QueueKey queueKey = waitingUser.getQueueKey();

        List<Long> cancelResult = executeNumberListScript(
                CANCEL_SCRIPT, List.of(userQueueKey, MatchingRedisKeys.queue(queueKey)), payload);

        long removedCount = numberAt(cancelResult, 0);
        int waitingCount = (int) numberAt(cancelResult, 1);

        if (removedCount == 0L) {
            throw new IllegalStateException("대기열에서 사용자를 제거하지 못했습니다.");
        }

        return new CancelResult(queueKey, waitingCount);
    }

    @Override
    public List<WaitingUser> pollMatchCandidates(QueueKey queueKey, int count) {
        List<String> payloads =
                executeStringListScript(POLL_SCRIPT, List.of(MatchingRedisKeys.queue(queueKey)), String.valueOf(count));

        if (payloads.isEmpty()) {
            return null;
        }

        return payloads.stream().map(serializer::readWaitingUser).toList();
    }

    @Override
    public void rollbackPolledUsers(QueueKey queueKey, List<WaitingUser> users) {
        if (users == null || users.isEmpty()) {
            return;
        }

        List<String> keys = new ArrayList<>();
        keys.add(MatchingRedisKeys.queue(queueKey));

        List<String> payloads = new ArrayList<>();

        // queue 복구와 userQueue 복구를 같은 payload 기준으로 함께 되돌린다.
        for (WaitingUser user : users) {
            keys.add(MatchingRedisKeys.userQueue(user.getUserId()));
            payloads.add(serializer.writeWaitingUser(user));
        }

        redisTemplate.execute(ROLLBACK_SCRIPT, keys, payloads.toArray());
    }

    @Override
    public MatchSession markAcceptPending(QueueKey queueKey, List<WaitingUser> matchedUsers, LocalDateTime deadline) {
        if (matchedUsers != null && !matchedUsers.isEmpty()) {
            // queue에서 handoff 된 사용자는 SEARCHING 사용자로 보이지 않게 userQueue key를 먼저 정리한다.
            redisTemplate.delete(matchedUsers.stream()
                    .map(WaitingUser::getUserId)
                    .map(MatchingRedisKeys::userQueue)
                    .toList());
        }

        return delegate.markAcceptPending(queueKey, matchedUsers, deadline);
    }

    @Override
    public MatchSession accept(Long matchId, Long userId) {
        return delegate.accept(matchId, userId);
    }

    @Override
    public MatchSession decline(Long matchId, Long userId) {
        return delegate.decline(matchId, userId);
    }

    @Override
    public RoomCreationAttempt tryBeginRoomCreation(Long matchId) {
        return delegate.tryBeginRoomCreation(matchId);
    }

    @Override
    public MatchSession markRoomReady(Long matchId, Long roomId) {
        return delegate.markRoomReady(matchId, roomId);
    }

    @Override
    public MatchSession expire(Long matchId) {
        return delegate.expire(matchId);
    }

    @Override
    public MatchSession cancelMatch(Long matchId) {
        return delegate.cancelMatch(matchId);
    }

    @Override
    public int getWaitingCount(QueueKey queueKey) {
        Long waitingCount = redisTemplate.opsForList().size(MatchingRedisKeys.queue(queueKey));
        return waitingCount == null ? 0 : waitingCount.intValue();
    }

    @Override
    public QueueStateV2Response getQueueStateV2(Long userId) {
        String payload = findActiveQueuePayload(userId);

        if (payload == null) {
            return new QueueStateV2Response(false, null, null, 0, REQUIRED_MATCH_SIZE);
        }

        WaitingUser waitingUser = serializer.readWaitingUser(payload);
        QueueKey queueKey = waitingUser.getQueueKey();

        return new QueueStateV2Response(
                true,
                queueKey.category(),
                queueKey.difficulty().name(),
                getWaitingCount(queueKey),
                REQUIRED_MATCH_SIZE);
    }

    @Override
    public MatchSession findMatchSessionByUserId(Long userId) {
        return delegate.findMatchSessionByUserId(userId);
    }

    @Override
    public List<Long> findExpiredAcceptPendingMatchIds(LocalDateTime now) {
        return delegate.findExpiredAcceptPendingMatchIds(now);
    }

    @Override
    public void clearTerminalMatch(Long matchId) {
        delegate.clearTerminalMatch(matchId);
    }

    @Override
    public void clearMatchedRoom(Long userId, Long roomId) {
        delegate.clearMatchedRoom(userId, roomId);
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
     * 설정 구조는 미리 받아 두되, 이번 단계에서는 동작 분기에만 사용한다.
     */
    MatchingStoreProperties storeProperties() {
        return storeProperties;
    }

    private void ensureJoinEligibility(Long userId) {
        MatchSession matchSession = delegate.findMatchSessionByUserId(userId);

        if (matchSession != null) {
            throw new ServiceException("409-1", "이미 진행 중인 매칭이 있습니다.");
        }
    }

    private String findActiveQueuePayload(Long userId) {
        String userQueueKey = MatchingRedisKeys.userQueue(userId);
        String payload = redisTemplate.opsForValue().get(userQueueKey);

        if (payload == null) {
            return null;
        }

        WaitingUser waitingUser = serializer.readWaitingUser(payload);
        String queueKey = MatchingRedisKeys.queue(waitingUser.getQueueKey());

        // userQueue만 남고 실제 queue list에는 없으면 stale 데이터로 보고 현재 사용자 기준으로 정리한다.
        Long contains = redisTemplate.execute(QUEUE_CONTAINS_SCRIPT, List.of(queueKey), payload);

        if (contains == null || contains == 0L) {
            redisTemplate.delete(userQueueKey);
            return null;
        }

        return payload;
    }

    @SuppressWarnings("unchecked")
    private List<Long> executeNumberListScript(RedisScript<List> script, List<String> keys, String... args) {
        List<Object> raw = (List<Object>) redisTemplate.execute(script, keys, (Object[]) args);

        if (raw == null) {
            return List.of();
        }

        return raw.stream().map(this::asLong).toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> executeStringListScript(RedisScript<List> script, List<String> keys, String... args) {
        List<Object> raw = (List<Object>) redisTemplate.execute(script, keys, (Object[]) args);

        if (raw == null) {
            return List.of();
        }

        return raw.stream().map(String::valueOf).toList();
    }

    private long numberAt(List<Long> values, int index) {
        return values.size() > index ? values.get(index) : 0L;
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }

        throw new IllegalStateException("Redis 스크립트 결과를 숫자로 변환하지 못했습니다.");
    }

    private static RedisScript<Long> longScript(String scriptText) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(scriptText);
        script.setResultType(Long.class);
        return script;
    }

    private static RedisScript<List> listScript(String scriptText) {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setScriptText(scriptText);
        script.setResultType(List.class);
        return script;
    }
}
