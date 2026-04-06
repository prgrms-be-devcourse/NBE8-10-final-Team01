package com.back.domain.matching.queue.store.redis;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.back.domain.matching.queue.model.MatchSession;
import com.back.domain.matching.queue.model.MatchSessionStatus;
import com.back.domain.matching.queue.model.QueueKey;
import com.back.domain.matching.queue.model.ReadyDecision;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * matching 상태를 Redis 문자열 값으로 저장할 때 사용할 직렬화 규칙을 모은다.
 *
 * 이번 단계에서는 StringRedisTemplate + JSON 저장 방식을 기준으로 고정한다.
 */
public class MatchingRedisSerializer {

    private final ObjectMapper objectMapper;

    public MatchingRedisSerializer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * match session 본문은 JSON 문자열 하나로 저장한다.
     */
    public String writeMatchSession(MatchSession matchSession) {
        return writeValue(MatchSessionDocument.from(matchSession), "match session");
    }

    /**
     * queue key 도 필요 시 문자열 값으로 저장할 수 있게 같은 규칙을 사용한다.
     */
    public String writeQueueKey(QueueKey queueKey) {
        return writeValue(queueKey, "queue key");
    }

    /**
     * Redis 에서 꺼낸 JSON 을 MatchSession 으로 복원한다.
     */
    public MatchSession readMatchSession(String value) {
        MatchSessionDocument document = readValue(value, MatchSessionDocument.class, "match session");
        return document.toModel();
    }

    /**
     * Redis 에서 꺼낸 JSON 을 QueueKey 로 복원한다.
     */
    public QueueKey readQueueKey(String value) {
        return readValue(value, QueueKey.class, "queue key");
    }

    private String writeValue(Object value, String targetName) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(targetName + " JSON 직렬화에 실패했습니다.", e);
        }
    }

    private <T> T readValue(String value, Class<T> targetType, String targetName) {
        try {
            return objectMapper.readValue(value, targetType);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(targetName + " JSON 역직렬화에 실패했습니다.", e);
        }
    }

    /**
     * MatchSession 의 계산 메서드가 JSON 에 섞이지 않도록
     * Redis 저장 전용 스냅샷 구조를 별도로 둔다.
     */
    private record MatchSessionDocument(
            Long matchId,
            QueueKey queueKey,
            List<Long> participantIds,
            Map<Long, String> participantNicknames,
            Map<Long, ReadyDecision> participantDecisions,
            MatchSessionStatus status,
            Long roomId,
            LocalDateTime deadline,
            LocalDateTime createdAt) {

        private static MatchSessionDocument from(MatchSession matchSession) {
            return new MatchSessionDocument(
                    matchSession.matchId(),
                    matchSession.queueKey(),
                    matchSession.participantIds(),
                    matchSession.participantNicknames(),
                    matchSession.participantDecisions(),
                    matchSession.status(),
                    matchSession.roomId(),
                    matchSession.deadline(),
                    matchSession.createdAt());
        }

        private MatchSession toModel() {
            return new MatchSession(
                    matchId,
                    queueKey,
                    participantIds,
                    participantNicknames,
                    participantDecisions,
                    status,
                    roomId,
                    deadline,
                    createdAt);
        }
    }
}
