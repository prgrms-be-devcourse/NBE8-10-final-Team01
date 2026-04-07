package com.back.domain.matching.queue.store.redis;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import com.back.domain.matching.queue.dto.QueueStateV2Response;
import com.back.domain.matching.queue.model.MatchSession;
import com.back.domain.matching.queue.model.MatchSessionStatus;
import com.back.domain.matching.queue.model.QueueKey;
import com.back.domain.matching.queue.model.ReadyDecision;
import com.back.domain.matching.queue.model.WaitingUser;
import com.back.domain.matching.queue.store.MatchStateStore;
import com.back.domain.matching.queue.store.MatchingStoreProperties;
import com.back.global.exception.ServiceException;

/**
 * Redis 기반 matching 상태 저장소
 *
 * queue 와 ready-check 모두 Redis 를 단일 원본으로 사용하고,
 * 복합 상태 전이는 Lua script 또는 Redis CAS 경계로 보호한다.
 */
@Primary
@Component
@ConditionalOnProperty(prefix = "matching.store", name = "type", havingValue = "redis")
public class RedisMatchStateStore implements MatchStateStore {

    private static final int REQUIRED_MATCH_SIZE = 4;
    private static final int MAX_SESSION_UPDATE_RETRY = 20;

    private static final RedisScript<Long> ENQUEUE_SCRIPT = longScript("""
            -- MATCHING:QUEUE_ENQUEUE
            if redis.call('EXISTS', KEYS[1]) == 1 then
                return -1
            end
            redis.call('SET', KEYS[1], ARGV[1])
            redis.call('RPUSH', KEYS[2], ARGV[1])
            return redis.call('LLEN', KEYS[2])
            """);

    private static final RedisScript<Long> QUEUE_CONTAINS_SCRIPT = longScript("""
            -- MATCHING:QUEUE_CONTAINS
            local pos = redis.call('LPOS', KEYS[1], ARGV[1])
            if pos then
                return 1
            end
            return 0
            """);

    private static final RedisScript<List> POLL_SCRIPT = listScript("""
            -- MATCHING:QUEUE_POLL
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
            -- MATCHING:QUEUE_CANCEL
            local removed = redis.call('LREM', KEYS[2], 1, ARGV[1])
            redis.call('DEL', KEYS[1])
            local size = redis.call('LLEN', KEYS[2])
            if size == 0 then
                redis.call('DEL', KEYS[2])
            end
            return {removed, size}
            """);

    private static final RedisScript<Long> ROLLBACK_SCRIPT = longScript("""
            -- MATCHING:QUEUE_ROLLBACK
            for i = #ARGV, 1, -1 do
                redis.call('LPUSH', KEYS[1], ARGV[i])
            end

            for i = 2, #KEYS do
                redis.call('SET', KEYS[i], ARGV[i - 1])
            end

            return redis.call('LLEN', KEYS[1])
            """);

    private static final RedisScript<String> MARK_ACCEPT_PENDING_SCRIPT = stringScript("""
            -- MATCHING:MATCH_MARK_ACCEPT_PENDING
            local participantCount = tonumber(ARGV[3])
            redis.call('SET', KEYS[1], ARGV[1])

            for i = 1, participantCount do
                redis.call('SET', KEYS[i + 1], ARGV[2])
            end

            for i = participantCount + 2, #KEYS do
                redis.call('DEL', KEYS[i])
            end

            return ARGV[1]
            """);

    private static final RedisScript<Long> COMPARE_AND_SET_SCRIPT = longScript("""
            -- MATCHING:MATCH_COMPARE_AND_SET
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return 0
            end
            redis.call('SET', KEYS[1], ARGV[2])
            return 1
            """);

    private static final RedisScript<Long> DELETE_IF_VALUE_SCRIPT = longScript("""
            -- MATCHING:DELETE_IF_VALUE
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then
                return 0
            end
            redis.call('DEL', KEYS[1])
            return 1
            """);

    private static final RedisScript<Long> CLEAR_TERMINAL_SCRIPT = longScript("""
            -- MATCHING:MATCH_CLEAR_TERMINAL
            local expected = ARGV[1]

            for i = 2, #KEYS do
                if redis.call('GET', KEYS[i]) == expected then
                    redis.call('DEL', KEYS[i])
                end
            end

            redis.call('DEL', KEYS[1])
            return 1
            """);

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

        // queue 복구와 userQueue 복구를 같은 payload 기준으로 한 번에 되돌린다.
        for (WaitingUser user : users) {
            keys.add(MatchingRedisKeys.userQueue(user.getUserId()));
            payloads.add(serializer.writeWaitingUser(user));
        }

        redisTemplate.execute(ROLLBACK_SCRIPT, keys, payloads.toArray());
    }

    @Override
    public MatchSession markAcceptPending(QueueKey queueKey, List<WaitingUser> matchedUsers, LocalDateTime deadline) {
        if (matchedUsers == null || matchedUsers.isEmpty()) {
            throw new IllegalArgumentException("matchedUsers 는 비어 있을 수 없습니다.");
        }

        Long matchId = redisTemplate.opsForValue().increment(MatchingRedisKeys.matchSequence());
        if (matchId == null) {
            throw new IllegalStateException("Redis match sequence 결과를 확인할 수 없습니다.");
        }

        List<Long> participantIds =
                matchedUsers.stream().map(WaitingUser::getUserId).toList();
        Map<Long, String> participantNicknames = new LinkedHashMap<>();
        matchedUsers.forEach(user -> participantNicknames.put(user.getUserId(), user.getNickname()));

        MatchSession matchSession =
                MatchSession.acceptPending(matchId, queueKey, participantIds, participantNicknames, deadline);
        String sessionJson = serializer.writeMatchSession(matchSession);

        List<String> keys = new ArrayList<>();
        keys.add(MatchingRedisKeys.match(matchId));
        participantIds.forEach(participantId -> keys.add(MatchingRedisKeys.userMatch(participantId)));
        participantIds.forEach(participantId -> keys.add(MatchingRedisKeys.userQueue(participantId)));

        String storedJson = redisTemplate.execute(
                MARK_ACCEPT_PENDING_SCRIPT,
                keys,
                sessionJson,
                String.valueOf(matchId),
                String.valueOf(participantIds.size()));

        if (storedJson == null || storedJson.isBlank()) {
            throw new IllegalStateException("Redis ready-check 세션 저장 결과를 확인할 수 없습니다.");
        }

        return serializer.readMatchSession(storedJson);
    }

    @Override
    public MatchSession accept(Long matchId, Long userId) {
        LocalDateTime now = LocalDateTime.now();

        return updateMatchSession(matchId, currentSession -> {
            ensureParticipant(currentSession, userId, "매치 참가자가 아닌 사용자는 수락할 수 없습니다.");

            if (currentSession.isExpiredAt(now)) {
                return currentSession.status() == MatchSessionStatus.ACCEPT_PENDING
                        ? currentSession.expired()
                        : currentSession;
            }

            if (currentSession.status() != MatchSessionStatus.ACCEPT_PENDING) {
                return currentSession;
            }

            if (currentSession.decisionOf(userId) == ReadyDecision.ACCEPTED) {
                return currentSession;
            }

            return currentSession.withDecision(userId, ReadyDecision.ACCEPTED);
        });
    }

    @Override
    public MatchSession decline(Long matchId, Long userId) {
        LocalDateTime now = LocalDateTime.now();

        return updateMatchSession(matchId, currentSession -> {
            ensureParticipant(currentSession, userId, "매치 참가자가 아닌 사용자는 거절할 수 없습니다.");

            if (currentSession.isExpiredAt(now)) {
                return currentSession.status() == MatchSessionStatus.ACCEPT_PENDING
                        ? currentSession.expired()
                        : currentSession;
            }

            if (currentSession.status() != MatchSessionStatus.ACCEPT_PENDING) {
                return currentSession;
            }

            return currentSession.withDecision(userId, ReadyDecision.DECLINED).cancelled();
        });
    }

    @Override
    public RoomCreationAttempt tryBeginRoomCreation(Long matchId) {
        String matchKey = MatchingRedisKeys.match(matchId);

        for (int retry = 0; retry < MAX_SESSION_UPDATE_RETRY; retry++) {
            String currentJson = redisTemplate.opsForValue().get(matchKey);
            MatchSession currentSession = requireMatchSession(currentJson);

            if (currentSession.status() != MatchSessionStatus.ACCEPT_PENDING) {
                return new RoomCreationAttempt(currentSession, false);
            }

            if (!currentSession.isAllAccepted()) {
                return new RoomCreationAttempt(currentSession, false);
            }

            MatchSession roomCreatingSession = currentSession.roomCreating();
            String updatedJson = serializer.writeMatchSession(roomCreatingSession);

            if (compareAndSet(matchKey, currentJson, updatedJson)) {
                return new RoomCreationAttempt(roomCreatingSession, true);
            }
        }

        throw new IllegalStateException("Redis room 생성 선점 상태를 갱신하지 못했습니다.");
    }

    @Override
    public MatchSession markRoomReady(Long matchId, Long roomId) {
        return updateMatchSession(matchId, currentSession -> {
            if (currentSession.status() == MatchSessionStatus.ROOM_READY) {
                return currentSession;
            }

            if (currentSession.status() != MatchSessionStatus.ROOM_CREATING
                    && currentSession.status() != MatchSessionStatus.ACCEPT_PENDING) {
                return currentSession;
            }

            return currentSession.roomReady(roomId);
        });
    }

    @Override
    public MatchSession expire(Long matchId) {
        return updateMatchSession(matchId, currentSession -> {
            if (currentSession.status() != MatchSessionStatus.ACCEPT_PENDING) {
                return currentSession;
            }

            return currentSession.expired();
        });
    }

    @Override
    public MatchSession cancelMatch(Long matchId) {
        return updateMatchSession(matchId, currentSession -> {
            if (currentSession.status() == MatchSessionStatus.CANCELLED) {
                return currentSession;
            }

            return currentSession.cancelled();
        });
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
        String userMatchKey = MatchingRedisKeys.userMatch(userId);
        String matchIdValue = redisTemplate.opsForValue().get(userMatchKey);

        if (matchIdValue == null || matchIdValue.isBlank()) {
            return null;
        }

        Long matchId = parseMatchIdValue(matchIdValue);
        String matchJson = redisTemplate.opsForValue().get(MatchingRedisKeys.match(matchId));

        if (matchJson == null || matchJson.isBlank()) {
            deleteIfValue(userMatchKey, matchIdValue);
            return null;
        }

        MatchSession matchSession = serializer.readMatchSession(matchJson);

        if (!matchSession.hasParticipant(userId)) {
            deleteIfValue(userMatchKey, matchIdValue);
            return null;
        }

        if (matchSession.status() == MatchSessionStatus.CLOSED) {
            deleteIfValue(userMatchKey, matchIdValue);
            return null;
        }

        if (matchSession.status() == MatchSessionStatus.CANCELLED
                || matchSession.status() == MatchSessionStatus.EXPIRED) {
            clearTerminalMatch(matchId);
            return null;
        }

        return matchSession;
    }

    @Override
    public List<Long> findExpiredAcceptPendingMatchIds(LocalDateTime now) {
        Set<String> keys = redisTemplate.keys(MatchingRedisKeys.matchPattern());

        if (keys == null || keys.isEmpty()) {
            return List.of();
        }

        List<Long> expiredMatchIds = new ArrayList<>();

        // deadline index 도입 전까지는 match 세션 key 들을 임시로 pattern scan 하며 만료 대상을 찾는다.
        for (String key : keys) {
            Long matchId = extractMatchIdFromKey(key);
            if (matchId == null) {
                continue;
            }

            String sessionJson = redisTemplate.opsForValue().get(key);
            if (sessionJson == null || sessionJson.isBlank()) {
                continue;
            }

            MatchSession matchSession = serializer.readMatchSession(sessionJson);
            if (matchSession.status() == MatchSessionStatus.ACCEPT_PENDING
                    && matchSession.deadline() != null
                    && !matchSession.deadline().isAfter(now)) {
                expiredMatchIds.add(matchSession.matchId());
            }
        }

        expiredMatchIds.sort(Long::compareTo);
        return expiredMatchIds;
    }

    @Override
    public void clearTerminalMatch(Long matchId) {
        String matchKey = MatchingRedisKeys.match(matchId);
        String matchJson = redisTemplate.opsForValue().get(matchKey);

        if (matchJson == null || matchJson.isBlank()) {
            removeUserMatchLinksByScan(matchId);
            return;
        }

        MatchSession matchSession = serializer.readMatchSession(matchJson);
        List<String> keys = new ArrayList<>();
        keys.add(matchKey);
        matchSession.participantIds().forEach(participantId -> keys.add(MatchingRedisKeys.userMatch(participantId)));

        redisTemplate.execute(CLEAR_TERMINAL_SCRIPT, keys, String.valueOf(matchId));
    }

    @Override
    public void clearMatchedRoom(Long userId, Long roomId) {
        if (roomId == null) {
            return;
        }

        String userMatchKey = MatchingRedisKeys.userMatch(userId);
        String matchIdValue = redisTemplate.opsForValue().get(userMatchKey);

        if (matchIdValue == null || matchIdValue.isBlank()) {
            return;
        }

        Long matchId = parseMatchIdValue(matchIdValue);
        String matchKey = MatchingRedisKeys.match(matchId);
        String matchJson = redisTemplate.opsForValue().get(matchKey);

        if (matchJson == null || matchJson.isBlank()) {
            deleteIfValue(userMatchKey, matchIdValue);
            return;
        }

        MatchSession matchSession = serializer.readMatchSession(matchJson);
        if (!roomId.equals(matchSession.roomId())) {
            return;
        }

        deleteIfValue(userMatchKey, matchIdValue);

        boolean hasRemainingReference = matchSession.participantIds().stream()
                .map(MatchingRedisKeys::userMatch)
                .map(key -> redisTemplate.opsForValue().get(key))
                .anyMatch(matchIdValue::equals);

        if (!hasRemainingReference) {
            compareAndDelete(matchKey, matchJson);
        }
    }

    private MatchSession updateMatchSession(Long matchId, Function<MatchSession, MatchSession> updater) {
        String matchKey = MatchingRedisKeys.match(matchId);

        for (int retry = 0; retry < MAX_SESSION_UPDATE_RETRY; retry++) {
            String currentJson = redisTemplate.opsForValue().get(matchKey);
            MatchSession currentSession = requireMatchSession(currentJson);
            MatchSession updatedSession = updater.apply(currentSession);

            if (updatedSession.equals(currentSession)) {
                return updatedSession;
            }

            String updatedJson = serializer.writeMatchSession(updatedSession);
            if (compareAndSet(matchKey, currentJson, updatedJson)) {
                return updatedSession;
            }
        }

        throw new IllegalStateException("Redis 매치 세션 상태를 갱신하지 못했습니다.");
    }

    private MatchSession requireMatchSession(String sessionJson) {
        if (sessionJson == null || sessionJson.isBlank()) {
            throw new IllegalStateException("존재하지 않는 매치 세션입니다.");
        }
        return serializer.readMatchSession(sessionJson);
    }

    private void ensureJoinEligibility(Long userId) {
        MatchSession matchSession = findMatchSessionByUserId(userId);

        if (matchSession != null) {
            throw new ServiceException("409-1", "이미 진행 중인 매칭이 있습니다.");
        }
    }

    private void ensureParticipant(MatchSession matchSession, Long userId, String message) {
        if (!matchSession.hasParticipant(userId)) {
            throw new IllegalStateException(message);
        }
    }

    private String findActiveQueuePayload(Long userId) {
        String userQueueKey = MatchingRedisKeys.userQueue(userId);
        String payload = redisTemplate.opsForValue().get(userQueueKey);

        if (payload == null || payload.isBlank()) {
            return null;
        }

        WaitingUser waitingUser = serializer.readWaitingUser(payload);
        String queueKey = MatchingRedisKeys.queue(waitingUser.getQueueKey());

        // userQueue 만 남고 실제 queue list 에는 없으면 stale 데이터로 보고 즉시 정리한다.
        Long contains = redisTemplate.execute(QUEUE_CONTAINS_SCRIPT, List.of(queueKey), payload);

        if (contains == null || contains == 0L) {
            redisTemplate.delete(userQueueKey);
            return null;
        }

        return payload;
    }

    private boolean compareAndSet(String key, String expectedValue, String updatedValue) {
        Long updated = redisTemplate.execute(COMPARE_AND_SET_SCRIPT, List.of(key), expectedValue, updatedValue);
        return updated != null && updated == 1L;
    }

    private boolean compareAndDelete(String key, String expectedValue) {
        Long deleted = redisTemplate.execute(DELETE_IF_VALUE_SCRIPT, List.of(key), expectedValue);
        return deleted != null && deleted == 1L;
    }

    private boolean deleteIfValue(String key, String expectedValue) {
        return compareAndDelete(key, expectedValue);
    }

    private void removeUserMatchLinksByScan(Long matchId) {
        String matchIdValue = String.valueOf(matchId);
        Set<String> keys = redisTemplate.keys(MatchingRedisKeys.userMatchPattern());

        if (keys == null || keys.isEmpty()) {
            return;
        }

        for (String key : keys) {
            String currentValue = redisTemplate.opsForValue().get(key);
            if (matchIdValue.equals(currentValue)) {
                deleteIfValue(key, matchIdValue);
            }
        }
    }

    private Long parseMatchIdValue(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("user:match 값이 올바른 matchId 가 아닙니다.", e);
        }
    }

    private Long extractMatchIdFromKey(String key) {
        if (key == null || !key.startsWith(MatchingRedisKeys.matchPrefix())) {
            return null;
        }

        String suffix = key.substring(MatchingRedisKeys.matchPrefix().length());
        if (suffix.isBlank() || !suffix.chars().allMatch(Character::isDigit)) {
            return null;
        }

        return Long.parseLong(suffix);
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

    private static RedisScript<String> stringScript(String scriptText) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptText(scriptText);
        script.setResultType(String.class);
        return script;
    }
}
