package com.back.domain.matching.queue.store.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.back.domain.matching.queue.model.Difficulty;
import com.back.domain.matching.queue.model.MatchSession;
import com.back.domain.matching.queue.model.MatchSessionStatus;
import com.back.domain.matching.queue.model.QueueKey;
import com.back.domain.matching.queue.model.ReadyDecision;
import com.fasterxml.jackson.databind.json.JsonMapper;

class MatchingRedisSerializerTest {

    private final MatchingRedisSerializer serializer =
            new MatchingRedisSerializer(JsonMapper.builder().findAndAddModules().build());

    @Test
    @DisplayName("QueueKey 는 JSON 으로 직렬화 후 다시 복원할 수 있다")
    void serializesQueueKeyRoundTrip() {
        QueueKey queueKey = new QueueKey("graph", Difficulty.HARD);

        String payload = serializer.writeQueueKey(queueKey);
        QueueKey restored = serializer.readQueueKey(payload);

        assertThat(restored).isEqualTo(queueKey);
    }

    @Test
    @DisplayName("MatchSession 은 JSON 으로 직렬화 후 다시 복원할 수 있다")
    void serializesMatchSessionRoundTrip() {
        MatchSession session = new MatchSession(
                15L,
                new QueueKey("dp", Difficulty.MEDIUM),
                List.of(1L, 2L, 3L, 4L),
                participantNicknames(),
                participantDecisions(),
                MatchSessionStatus.ACCEPT_PENDING,
                null,
                LocalDateTime.of(2026, 4, 6, 12, 30, 0),
                LocalDateTime.of(2026, 4, 6, 12, 0, 0));

        String payload = serializer.writeMatchSession(session);
        MatchSession restored = serializer.readMatchSession(payload);

        assertThat(restored).isEqualTo(session);
    }

    @Test
    @DisplayName("비어 있는 MatchSession JSON 은 명시적인 예외로 거부한다")
    void rejectsBlankMatchSessionJson() {
        assertThatThrownBy(() -> serializer.readMatchSession(" "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("match session JSON 값이 비어 있습니다.");
    }

    @Test
    @DisplayName("없는 QueueKey JSON 은 명시적인 예외로 거부한다")
    void rejectsNullQueueKeyJson() {
        assertThatThrownBy(() -> serializer.readQueueKey(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("queue key JSON 값이 비어 있습니다.");
    }

    private Map<Long, String> participantNicknames() {
        Map<Long, String> nicknames = new LinkedHashMap<>();
        nicknames.put(1L, "m1");
        nicknames.put(2L, "m2");
        nicknames.put(3L, "m3");
        nicknames.put(4L, "m4");
        return nicknames;
    }

    private Map<Long, ReadyDecision> participantDecisions() {
        Map<Long, ReadyDecision> decisions = new LinkedHashMap<>();
        decisions.put(1L, ReadyDecision.ACCEPTED);
        decisions.put(2L, ReadyDecision.PENDING);
        decisions.put(3L, ReadyDecision.PENDING);
        decisions.put(4L, ReadyDecision.PENDING);
        return decisions;
    }
}
