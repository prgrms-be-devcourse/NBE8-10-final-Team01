package com.back.domain.matching.queue.store.redis;

import java.util.Objects;

import com.back.domain.matching.queue.model.QueueKey;

/**
 * matching Redis key 규칙을 한 곳에 모아 둔다.
 *
 * 이후 하위 이슈에서 Redis 저장소 구현이 더 확장되더라도
 * key 조합 규칙은 이 유틸만 기준으로 사용한다.
 */
public final class MatchingRedisKeys {

    private static final String PREFIX = "matching";
    private static final String QUEUE_PREFIX = PREFIX + ":queue";
    private static final String USER_QUEUE_PREFIX = PREFIX + ":user:queue";
    private static final String USER_MATCH_PREFIX = PREFIX + ":user:match";
    private static final String MATCH_PREFIX = PREFIX + ":match";
    private static final String MATCH_DEADLINE_KEY = MATCH_PREFIX + ":deadline";
    private static final String MATCH_SEQUENCE_KEY = MATCH_PREFIX + ":seq";

    private MatchingRedisKeys() {}

    /**
     * queue 대기열은 category + difficulty 조합으로 구분한다.
     */
    public static String queue(QueueKey queueKey) {
        Objects.requireNonNull(queueKey, "queueKey must not be null");
        Objects.requireNonNull(queueKey.category(), "queueKey.category must not be null");
        Objects.requireNonNull(queueKey.difficulty(), "queueKey.difficulty must not be null");
        return QUEUE_PREFIX + ":" + queueKey.category() + ":"
                + queueKey.difficulty().name();
    }

    /**
     * user -> queue 연결은 사용자별 단일 key 로 고정한다.
     */
    public static String userQueue(Long userId) {
        return USER_QUEUE_PREFIX + ":" + requireId(userId, "userId");
    }

    /**
     * user -> match 연결은 사용자별 단일 key 로 고정한다.
     */
    public static String userMatch(Long userId) {
        return USER_MATCH_PREFIX + ":" + requireId(userId, "userId");
    }

    /**
     * ready-check session 본문은 matchId 로 조회한다.
     */
    public static String match(Long matchId) {
        return MATCH_PREFIX + ":" + requireId(matchId, "matchId");
    }

    /**
     * ready-check session key 를 훑을 때 사용하는 임시 패턴이다.
     */
    public static String matchPattern() {
        return MATCH_PREFIX + ":*";
    }

    /**
     * user:match 인덱스 전체를 훑을 때 사용하는 패턴이다.
     */
    public static String userMatchPattern() {
        return USER_MATCH_PREFIX + ":*";
    }

    /**
     * match session key prefix 를 외부 helper 에서 재사용할 수 있게 노출한다.
     */
    public static String matchPrefix() {
        return MATCH_PREFIX + ":";
    }

    /**
     * deadline index 는 단일 sorted set key 로 유지한다.
     */
    public static String matchDeadline() {
        return MATCH_DEADLINE_KEY;
    }

    /**
     * match sequence 는 단일 증가 key 로 유지한다.
     */
    public static String matchSequence() {
        return MATCH_SEQUENCE_KEY;
    }

    private static Long requireId(Long id, String fieldName) {
        return Objects.requireNonNull(id, fieldName + " must not be null");
    }
}
