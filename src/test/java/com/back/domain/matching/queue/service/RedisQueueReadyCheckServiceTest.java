package com.back.domain.matching.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.back.domain.battle.battleroom.service.BattleRoomService;
import com.back.domain.matching.queue.adapter.QueueProblemPicker;
import com.back.domain.matching.queue.dto.MatchStateV2Response;
import com.back.domain.matching.queue.dto.MatchStatus;
import com.back.domain.matching.queue.dto.QueueJoinRequest;
import com.back.domain.matching.queue.dto.QueueStateV2Response;
import com.back.domain.matching.queue.dto.QueueStatusResponse;
import com.back.domain.matching.queue.model.Difficulty;
import com.back.domain.matching.queue.model.QueueKey;
import com.back.domain.matching.queue.model.WaitingUser;
import com.back.domain.matching.queue.store.InMemoryMatchStateStore;
import com.back.domain.matching.queue.store.MatchingStoreProperties;
import com.back.domain.matching.queue.store.redis.FakeStringRedisTemplate;
import com.back.domain.matching.queue.store.redis.MatchingRedisSerializer;
import com.back.domain.matching.queue.store.redis.RedisMatchStateStore;
import com.fasterxml.jackson.databind.json.JsonMapper;

class RedisQueueReadyCheckServiceTest {

    private final BattleRoomService battleRoomService = mock(BattleRoomService.class);
    private final QueueProblemPicker queueProblemPicker = mock(QueueProblemPicker.class);
    private final MatchingEventPublisher matchingEventPublisher = mock(MatchingEventPublisher.class);

    private FakeStringRedisTemplate redisTemplate;
    private MatchingRedisSerializer serializer;
    private ReadyCheckService readyCheckService;

    @BeforeEach
    void setUp() {
        redisTemplate = new FakeStringRedisTemplate();
        serializer = new MatchingRedisSerializer(
                JsonMapper.builder().findAndAddModules().build());
        readyCheckService = new ReadyCheckService(
                battleRoomService,
                queueProblemPicker,
                new RedisMatchStateStore(
                        redisTemplate, serializer, matchingStoreProperties(), new InMemoryMatchStateStore()),
                matchingEventPublisher);
    }

    @Test
    @DisplayName("Redis queue 경로에서도 queue/me 는 SEARCHING 동안 waitingCount와 requiredCount를 반환한다")
    void getMyQueueStateV2_returnsSearchingInfo() {
        QueueStatusResponse response = joinUser(1L);

        QueueStateV2Response queueState = readyCheckService.getMyQueueStateV2(1L);

        assertThat(response.getWaitingCount()).isEqualTo(1);
        assertThat(queueState.inQueue()).isTrue();
        assertThat(queueState.category()).isEqualTo("ARRAY");
        assertThat(queueState.difficulty()).isEqualTo("EASY");
        assertThat(queueState.waitingCount()).isEqualTo(1);
        assertThat(queueState.requiredCount()).isEqualTo(4);
        verify(matchingEventPublisher).publishQueueStateChanged(any(QueueKey.class), eq(1));
    }

    @Test
    @DisplayName("Redis queue 경로에서도 cancel 후 감소한 waitingCount를 queue topic 이벤트로 발행한다")
    void cancelQueueV2_publishesQueueStateChanged() {
        joinUser(1L);
        joinUser(2L);

        QueueStatusResponse response = readyCheckService.cancelQueueV2(2L);

        assertThat(response.getWaitingCount()).isEqualTo(1);
        verify(matchingEventPublisher, times(2)).publishQueueStateChanged(any(QueueKey.class), eq(1));
        verify(matchingEventPublisher, times(1)).publishQueueStateChanged(any(QueueKey.class), eq(2));
    }

    @Test
    @DisplayName("Redis queue 경로에서도 4번째 join 시 matched 4명에게 READY_CHECK_STARTED를 발행한다")
    void joinQueueV2_publishesReadyCheckStarted_whenFourthUserJoins() {
        joinUser(1L);
        joinUser(2L);
        joinUser(3L);

        readyCheckService.joinQueueV2(4L, "m4", createRequest("Array", Difficulty.EASY));

        MatchStateV2Response matchState = readyCheckService.getMyMatchStateV2(1L);
        ArgumentCaptor<Long> userIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<MatchStateV2Response> responseCaptor = ArgumentCaptor.forClass(MatchStateV2Response.class);

        assertThat(readyCheckService.getMyQueueStateV2(1L).inQueue()).isFalse();
        assertThat(matchState.status()).isEqualTo(MatchStatus.ACCEPT_PENDING);

        verify(matchingEventPublisher, times(4))
                .publishReadyCheckStarted(userIdCaptor.capture(), responseCaptor.capture());

        assertThat(userIdCaptor.getAllValues()).containsExactlyInAnyOrder(1L, 2L, 3L, 4L);
        assertThat(responseCaptor.getAllValues()).allSatisfy(response -> {
            assertThat(response.status()).isEqualTo(MatchStatus.ACCEPT_PENDING);
            assertThat(response.readyCheck()).isNotNull();
            assertThat(response.readyCheck().acceptedCount()).isEqualTo(0);
            assertThat(response.readyCheck().requiredCount()).isEqualTo(4);
        });
    }

    @Test
    @DisplayName("Redis queue 경로에서도 ready-check 세션 생성 실패 시 rollback 후 queue waitingCount 복구 이벤트를 발행한다")
    void joinQueueV2_publishesRecoveredQueueEvent_whenMarkAcceptPendingFails() {
        ExplodingRedisMatchStateStore failingStore =
                new ExplodingRedisMatchStateStore(redisTemplate, serializer, matchingStoreProperties());
        MatchingEventPublisher failingPublisher = mock(MatchingEventPublisher.class);
        ReadyCheckService failingService =
                new ReadyCheckService(battleRoomService, queueProblemPicker, failingStore, failingPublisher);
        QueueKey queueKey = new QueueKey("Array", Difficulty.EASY);

        failingService.joinQueueV2(1L, "m1", createRequest("Array", Difficulty.EASY));
        failingService.joinQueueV2(2L, "m2", createRequest("Array", Difficulty.EASY));
        failingService.joinQueueV2(3L, "m3", createRequest("Array", Difficulty.EASY));

        assertThatThrownBy(() -> failingService.joinQueueV2(4L, "m4", createRequest("Array", Difficulty.EASY)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("mark failed");

        assertThat(failingStore.getWaitingCount(queueKey)).isEqualTo(4);
        verify(failingPublisher).publishQueueStateChanged(queueKey, 4);
    }

    private MatchingStoreProperties matchingStoreProperties() {
        MatchingStoreProperties properties = new MatchingStoreProperties();
        properties.setType(MatchingStoreProperties.StoreType.REDIS);
        return properties;
    }

    private QueueJoinRequest createRequest(String category, Difficulty difficulty) {
        return new QueueJoinRequest(category, difficulty);
    }

    private QueueStatusResponse joinUser(Long userId) {
        return readyCheckService.joinQueueV2(userId, "m" + userId, createRequest("Array", Difficulty.EASY));
    }

    private static final class ExplodingRedisMatchStateStore extends RedisMatchStateStore {

        private ExplodingRedisMatchStateStore(
                FakeStringRedisTemplate redisTemplate,
                MatchingRedisSerializer serializer,
                MatchingStoreProperties properties) {
            super(redisTemplate, serializer, properties, new InMemoryMatchStateStore());
        }

        @Override
        public com.back.domain.matching.queue.model.MatchSession markAcceptPending(
                QueueKey queueKey, List<WaitingUser> matchedUsers, LocalDateTime deadline) {
            throw new RuntimeException("mark failed");
        }
    }
}
