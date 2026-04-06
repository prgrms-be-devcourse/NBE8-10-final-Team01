package com.back.domain.matching.queue.store.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.back.domain.matching.queue.dto.QueueStateV2Response;
import com.back.domain.matching.queue.model.Difficulty;
import com.back.domain.matching.queue.model.MatchSession;
import com.back.domain.matching.queue.model.MatchSessionStatus;
import com.back.domain.matching.queue.model.QueueKey;
import com.back.domain.matching.queue.model.WaitingUser;
import com.back.domain.matching.queue.store.InMemoryMatchStateStore;
import com.back.domain.matching.queue.store.MatchStateStore;
import com.back.domain.matching.queue.store.MatchingStoreProperties;
import com.back.global.exception.ServiceException;
import com.fasterxml.jackson.databind.json.JsonMapper;

class RedisMatchStateStoreTest {

    private FakeStringRedisTemplate redisTemplate;
    private MatchingRedisSerializer serializer;
    private RedisMatchStateStore store;
    private InMemoryMatchStateStore delegate;

    @BeforeEach
    void setUp() {
        redisTemplate = new FakeStringRedisTemplate();
        serializer = new MatchingRedisSerializer(
                JsonMapper.builder().findAndAddModules().build());
        delegate = new InMemoryMatchStateStore();

        MatchingStoreProperties properties = new MatchingStoreProperties();
        properties.setType(MatchingStoreProperties.StoreType.REDIS);

        store = new RedisMatchStateStore(redisTemplate, serializer, properties, delegate);
    }

    @Test
    @DisplayName("enqueue 는 WaitingUser payload 를 Redis queue 와 userQueue index 에 함께 저장한다")
    void enqueue_storesWaitingUserPayloadInRedis() {
        QueueKey queueKey = queueKey();

        int currentSize = store.enqueue(1L, "m1", queueKey);

        String userQueuePayload = redisTemplate.opsForValue().get(MatchingRedisKeys.userQueue(1L));
        String queuePayload = redisTemplate.opsForList().index(MatchingRedisKeys.queue(queueKey), 0);

        assertThat(currentSize).isEqualTo(1);
        assertThat(userQueuePayload).isNotNull();
        assertThat(queuePayload).isEqualTo(userQueuePayload);
        assertThat(serializer.readWaitingUser(userQueuePayload).getQueueKey()).isEqualTo(queueKey);
    }

    @Test
    @DisplayName("중복 enqueue 는 기존과 같은 예외로 차단한다")
    void enqueue_rejectsDuplicateUser() {
        QueueKey queueKey = queueKey();
        store.enqueue(1L, "m1", queueKey);

        assertThatThrownBy(() -> store.enqueue(1L, "m1", queueKey))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("이미 매칭 대기열에 참가 중인 사용자입니다.");
    }

    @Test
    @DisplayName("cancel 은 Redis queue 와 userQueue index 를 함께 정리하고 waitingCount 를 반환한다")
    void cancel_removesRedisQueueState() {
        QueueKey queueKey = queueKey();
        store.enqueue(1L, "m1", queueKey);
        store.enqueue(2L, "m2", queueKey);

        MatchStateStore.CancelResult cancelResult = store.cancel(2L);

        assertThat(cancelResult.queueKey()).isEqualTo(queueKey);
        assertThat(cancelResult.waitingCount()).isEqualTo(1);
        assertThat(redisTemplate.opsForValue().get(MatchingRedisKeys.userQueue(2L)))
                .isNull();
        assertThat(store.getWaitingCount(queueKey)).isEqualTo(1);
    }

    @Test
    @DisplayName("queue/me 는 Redis 기준 SEARCHING snapshot 을 반환한다")
    void getQueueStateV2_readsSearchingInfoFromRedis() {
        store.enqueue(1L, "m1", queueKey());

        QueueStateV2Response response = store.getQueueStateV2(1L);

        assertThat(response.inQueue()).isTrue();
        assertThat(response.category()).isEqualTo("ARRAY");
        assertThat(response.difficulty()).isEqualTo("EASY");
        assertThat(response.waitingCount()).isEqualTo(1);
        assertThat(response.requiredCount()).isEqualTo(4);
    }

    @Test
    @DisplayName("인원이 부족하면 pollMatchCandidates 는 null 을 반환한다")
    void pollMatchCandidates_returnsNullWhenQueueIsShort() {
        QueueKey queueKey = queueKey();
        store.enqueue(1L, "m1", queueKey);
        store.enqueue(2L, "m2", queueKey);
        store.enqueue(3L, "m3", queueKey);

        List<WaitingUser> matchedUsers = store.pollMatchCandidates(queueKey, 4);

        assertThat(matchedUsers).isNull();
    }

    @Test
    @DisplayName("pollMatchCandidates 는 Redis queue 에서 FIFO 순서로 4명을 꺼낸다")
    void pollMatchCandidates_returnsFifoUsers() {
        QueueKey queueKey = queueKey();
        enqueueUsers(queueKey, 4);

        List<WaitingUser> matchedUsers = store.pollMatchCandidates(queueKey, 4);

        assertThat(matchedUsers).extracting(WaitingUser::getUserId).containsExactly(1L, 2L, 3L, 4L);
        assertThat(store.getWaitingCount(queueKey)).isEqualTo(0);
    }

    @Test
    @DisplayName("rollbackPolledUsers 는 queue 순서와 userQueue index 를 함께 복구한다")
    void rollbackPolledUsers_restoresQueueOrderAndUserQueue() {
        QueueKey queueKey = queueKey();
        enqueueUsers(queueKey, 4);
        List<WaitingUser> matchedUsers = store.pollMatchCandidates(queueKey, 4);

        store.rollbackPolledUsers(queueKey, matchedUsers);

        List<WaitingUser> restoredUsers = store.pollMatchCandidates(queueKey, 4);

        assertThat(restoredUsers).extracting(WaitingUser::getUserId).containsExactly(1L, 2L, 3L, 4L);
        assertThat(redisTemplate.opsForValue().get(MatchingRedisKeys.userQueue(1L)))
                .isNotNull();
    }

    @Test
    @DisplayName("markAcceptPending 은 userQueue Redis key 를 정리하고 ready-check 본문은 인메모리 delegate 에 저장한다")
    void markAcceptPending_handsOffToInMemoryDelegate() {
        QueueKey queueKey = queueKey();
        enqueueUsers(queueKey, 4);
        List<WaitingUser> matchedUsers = store.pollMatchCandidates(queueKey, 4);

        MatchSession matchSession =
                store.markAcceptPending(queueKey, matchedUsers, LocalDateTime.of(2026, 4, 6, 12, 30, 0));

        assertThat(matchSession.status()).isEqualTo(MatchSessionStatus.ACCEPT_PENDING);
        assertThat(redisTemplate.opsForValue().get(MatchingRedisKeys.userQueue(1L)))
                .isNull();
        assertThat(delegate.findMatchSessionByUserId(1L)).isNotNull();
    }

    private QueueKey queueKey() {
        return new QueueKey("Array", Difficulty.EASY);
    }

    private void enqueueUsers(QueueKey queueKey, int count) {
        for (long userId = 1L; userId <= count; userId++) {
            store.enqueue(userId, "m" + userId, queueKey);
        }
    }
}
