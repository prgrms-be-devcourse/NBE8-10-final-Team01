package com.back.domain.matching.queue.store.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.back.domain.matching.queue.model.Difficulty;
import com.back.domain.matching.queue.model.QueueKey;

class MatchingRedisKeysTest {

    @Test
    @DisplayName("queue key 는 category 와 difficulty 조합으로 생성된다")
    void buildsQueueKey() {
        QueueKey queueKey = new QueueKey("array", Difficulty.EASY);

        assertThat(MatchingRedisKeys.queue(queueKey)).isEqualTo("matching:queue:ARRAY:EASY");
    }

    @Test
    @DisplayName("user 와 match 관련 key 는 단일 규칙으로 생성된다")
    void buildsUserAndMatchKeys() {
        assertThat(MatchingRedisKeys.userQueue(7L)).isEqualTo("matching:user:queue:7");
        assertThat(MatchingRedisKeys.userMatch(7L)).isEqualTo("matching:user:match:7");
        assertThat(MatchingRedisKeys.match(11L)).isEqualTo("matching:match:11");
        assertThat(MatchingRedisKeys.matchDeadline()).isEqualTo("matching:match:deadline");
        assertThat(MatchingRedisKeys.matchSequence()).isEqualTo("matching:match:seq");
    }
}
