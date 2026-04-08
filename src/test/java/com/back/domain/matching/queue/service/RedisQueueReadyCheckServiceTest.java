package com.back.domain.matching.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.back.domain.battle.battleroom.dto.CreateRoomRequest;
import com.back.domain.battle.battleroom.dto.CreateRoomResponse;
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
import com.back.domain.matching.queue.store.MatchingStoreProperties;
import com.back.domain.matching.queue.store.redis.FakeStringRedisTemplate;
import com.back.domain.matching.queue.store.redis.MatchingRedisKeys;
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
    private RedisMatchStateStore store;

    @BeforeEach
    void setUp() {
        redisTemplate = new FakeStringRedisTemplate();
        serializer = new MatchingRedisSerializer(
                JsonMapper.builder().findAndAddModules().build());
        store = new RedisMatchStateStore(redisTemplate, serializer, matchingStoreProperties());
        readyCheckService = new ReadyCheckService(battleRoomService, queueProblemPicker, store, matchingEventPublisher);

        when(queueProblemPicker.pick(any(QueueKey.class), anyList())).thenReturn(1L);
    }

    @Test
    @DisplayName("Redis queue 경로에서는 queue/me 가 SEARCHING 동안 waitingCount 와 requiredCount 를 반환한다")
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
    @DisplayName("Redis queue 경로에서는 cancel 시 감소한 waitingCount 를 queue topic 이벤트로 발행한다")
    void cancelQueueV2_publishesQueueStateChanged() {
        joinUser(1L);
        joinUser(2L);

        QueueStatusResponse response = readyCheckService.cancelQueueV2(2L);

        assertThat(response.getWaitingCount()).isEqualTo(1);
        verify(matchingEventPublisher, times(2)).publishQueueStateChanged(any(QueueKey.class), eq(1));
        verify(matchingEventPublisher, times(1)).publishQueueStateChanged(any(QueueKey.class), eq(2));
    }

    @Test
    @DisplayName("4번째 join 이후 matches/me 는 Redis ready-check 세션을 읽어 ACCEPT_PENDING 을 반환한다")
    void getMyMatchStateV2_returnsAcceptPending_whenFourthUserJoins() {
        joinUser(1L);
        joinUser(2L);
        joinUser(3L);
        QueueStatusResponse fourthResponse = joinUser(4L);

        MatchStateV2Response response = readyCheckService.getMyMatchStateV2(1L);

        assertThat(fourthResponse.getWaitingCount()).isEqualTo(0);
        assertThat(readyCheckService.getMyQueueStateV2(1L).inQueue()).isFalse();
        assertThat(response.status()).isEqualTo(MatchStatus.ACCEPT_PENDING);
        assertThat(response.room()).isNull();
        assertThat(response.readyCheck()).isNotNull();
        assertThat(response.readyCheck().acceptedCount()).isEqualTo(0);
        assertThat(response.readyCheck().requiredCount()).isEqualTo(4);
        assertThat(response.readyCheck().participants())
                .extracting(participant -> participant.nickname())
                .containsExactly("m1", "m2", "m3", "m4");
    }

    @Test
    @DisplayName("4번째 join 시 matched 4명에게 READY_CHECK_STARTED 를 발행한다")
    void joinQueueV2_publishesReadyCheckStarted_whenFourthUserJoins() {
        joinUser(1L);
        joinUser(2L);
        joinUser(3L);

        readyCheckService.joinQueueV2(4L, "m4", createRequest("Array", Difficulty.EASY));

        ArgumentCaptor<Long> userIdCaptor = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<MatchStateV2Response> responseCaptor = ArgumentCaptor.forClass(MatchStateV2Response.class);

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
    @DisplayName("일반 accept 는 matched 4명에게 READY_DECISION_CHANGED 를 발행한다")
    void acceptMatch_publishesReadyDecisionChanged() {
        Long matchId = createAcceptPendingMatch();
        clearInvocations(matchingEventPublisher);

        MatchStateV2Response response = readyCheckService.acceptMatch(1L, matchId);

        assertThat(response.status()).isEqualTo(MatchStatus.ACCEPT_PENDING);
        assertThat(response.readyCheck().acceptedCount()).isEqualTo(1);
        assertThat(response.readyCheck().acceptedByMe()).isTrue();
        verify(matchingEventPublisher, times(4)).publishReadyDecisionChanged(any(), any());
    }

    @Test
    @DisplayName("백엔드 재시작 후에도 Redis ready-check 세션은 matches/me 로 복구된다")
    void getMyMatchStateV2_recoversFromRedisAfterRestart() {
        Long matchId = createAcceptPendingMatch();
        ReadyCheckService restartedService = new ReadyCheckService(
                battleRoomService,
                queueProblemPicker,
                new RedisMatchStateStore(redisTemplate, serializer, matchingStoreProperties()),
                matchingEventPublisher);

        MatchStateV2Response response = restartedService.getMyMatchStateV2(1L);

        assertThat(response.status()).isEqualTo(MatchStatus.ACCEPT_PENDING);
        assertThat(response.readyCheck().matchId()).isEqualTo(matchId);
    }

    @Test
    @DisplayName("전원 수락이 완료되면 ROOM_READY 와 roomId 를 반환한다")
    void acceptMatch_returnsRoomReady_whenAllUsersAccepted() {
        when(battleRoomService.createRoom(any(CreateRoomRequest.class)))
                .thenReturn(new CreateRoomResponse(100L, "WAITING"));

        joinUser(1L);
        joinUser(2L);
        joinUser(3L);
        joinUser(4L);

        Long matchId = readyCheckService.getMyMatchStateV2(1L).readyCheck().matchId();

        readyCheckService.acceptMatch(1L, matchId);
        readyCheckService.acceptMatch(2L, matchId);
        readyCheckService.acceptMatch(3L, matchId);
        clearInvocations(matchingEventPublisher);
        MatchStateV2Response response = readyCheckService.acceptMatch(4L, matchId);

        assertThat(response.status()).isEqualTo(MatchStatus.ROOM_READY);
        assertThat(response.room()).isNotNull();
        assertThat(response.room().roomId()).isEqualTo(100L);
        assertThat(response.readyCheck().acceptedCount()).isEqualTo(4);
        assertThat(redisTemplate.opsForZSet().score(MatchingRedisKeys.matchDeadline(), String.valueOf(matchId)))
                .isNull();
        assertThat(redisTemplate.opsForValue().get(MatchingRedisKeys.match(matchId)))
                .isNotNull();
        verify(battleRoomService, times(1)).createRoom(any(CreateRoomRequest.class));
        verify(matchingEventPublisher, times(4)).publishRoomReady(any(), any());
    }

    @Test
    @DisplayName("한 명이라도 거절하면 세션 전체가 CANCELLED 로 종료된다")
    void declineMatch_returnsCancelled() {
        joinUser(1L);
        joinUser(2L);
        joinUser(3L);
        joinUser(4L);

        Long matchId = readyCheckService.getMyMatchStateV2(1L).readyCheck().matchId();
        clearInvocations(matchingEventPublisher);

        MatchStateV2Response response = readyCheckService.declineMatch(2L, matchId);

        assertThat(response.status()).isEqualTo(MatchStatus.CANCELLED);
        assertThat(response.message()).isNotNull();
        assertThat(readyCheckService.getMyMatchStateV2(1L).status()).isEqualTo(MatchStatus.IDLE);
        assertThat(redisTemplate.opsForZSet().score(MatchingRedisKeys.matchDeadline(), String.valueOf(matchId)))
                .isNull();
        assertThat(redisTemplate.opsForValue().get(MatchingRedisKeys.match(matchId)))
                .isNull();
        verify(matchingEventPublisher, times(4)).publishMatchCancelled(any(), any());
    }

    @Test
    @DisplayName("만료 스케줄러는 Redis ready-check 세션을 찾아 MATCH_EXPIRED 를 발행하고 즉시 정리한다")
    void expireTimedOutMatches_publishesExpiredAndClearsSession() {
        QueueKey queueKey = new QueueKey("Array", Difficulty.EASY);
        List<WaitingUser> users = List.of(
                new WaitingUser(1L, "m1", queueKey),
                new WaitingUser(2L, "m2", queueKey),
                new WaitingUser(3L, "m3", queueKey),
                new WaitingUser(4L, "m4", queueKey));

        var matchSession =
                store.markAcceptPending(queueKey, users, LocalDateTime.now().minusSeconds(1));
        clearInvocations(matchingEventPublisher);

        readyCheckService.expireTimedOutMatches();

        assertThat(readyCheckService.getMyMatchStateV2(1L).status()).isEqualTo(MatchStatus.IDLE);
        assertThat(readyCheckService.getMyQueueStateV2(1L).inQueue()).isFalse();
        assertThat(redisTemplate
                        .opsForZSet()
                        .score(MatchingRedisKeys.matchDeadline(), String.valueOf(matchSession.matchId())))
                .isNull();
        verify(matchingEventPublisher, times(4)).publishMatchExpired(any(), any());
    }

    @Test
    @DisplayName("decline 후 expire 스케줄러는 같은 match 를 중복 만료 처리하지 않는다")
    void expireTimedOutMatches_doesNotPublishExpiredAgain_afterDecline() {
        joinUser(1L);
        joinUser(2L);
        joinUser(3L);
        joinUser(4L);

        Long matchId = readyCheckService.getMyMatchStateV2(1L).readyCheck().matchId();
        readyCheckService.declineMatch(2L, matchId);
        clearInvocations(matchingEventPublisher);

        readyCheckService.expireTimedOutMatches();

        assertThat(redisTemplate.opsForZSet().score(MatchingRedisKeys.matchDeadline(), String.valueOf(matchId)))
                .isNull();
        verify(matchingEventPublisher, times(0)).publishMatchExpired(any(), any());
    }

    @Test
    @DisplayName("마지막 accept 경쟁에서도 room 은 한 번만 생성된다")
    void acceptMatch_createsRoomOnlyOnce_whenLastAcceptsRace() throws Exception {
        AtomicInteger createRoomCallCount = new AtomicInteger();
        when(battleRoomService.createRoom(any(CreateRoomRequest.class))).thenAnswer(invocation -> {
            createRoomCallCount.incrementAndGet();
            Thread.sleep(200);
            return new CreateRoomResponse(100L, "WAITING");
        });

        joinUser(1L);
        joinUser(2L);
        joinUser(3L);
        joinUser(4L);

        Long matchId = readyCheckService.getMyMatchStateV2(1L).readyCheck().matchId();

        readyCheckService.acceptMatch(1L, matchId);
        readyCheckService.acceptMatch(2L, matchId);

        CountDownLatch startLatch = new CountDownLatch(1);
        ExecutorService executorService = Executors.newFixedThreadPool(2);

        try {
            Future<MatchStateV2Response> user3Future = executorService.submit(() -> {
                startLatch.await();
                return readyCheckService.acceptMatch(3L, matchId);
            });
            Future<MatchStateV2Response> user4Future = executorService.submit(() -> {
                startLatch.await();
                return readyCheckService.acceptMatch(4L, matchId);
            });

            startLatch.countDown();

            MatchStateV2Response user3Response = user3Future.get(5, TimeUnit.SECONDS);
            MatchStateV2Response user4Response = user4Future.get(5, TimeUnit.SECONDS);
            MatchStateV2Response finalState = readyCheckService.getMyMatchStateV2(1L);

            assertThat(createRoomCallCount.get()).isEqualTo(1);
            assertThat(user3Response.status()).isIn(MatchStatus.ACCEPT_PENDING, MatchStatus.ROOM_READY);
            assertThat(user4Response.status()).isIn(MatchStatus.ACCEPT_PENDING, MatchStatus.ROOM_READY);
            assertThat(finalState.status()).isEqualTo(MatchStatus.ROOM_READY);
            assertThat(finalState.room()).isNotNull();
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    @DisplayName("ready-check 세션 생성 실패 시 rollback 후 queue waitingCount 복구 이벤트를 발행한다")
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

    private Long createAcceptPendingMatch() {
        joinUser(1L);
        joinUser(2L);
        joinUser(3L);
        joinUser(4L);
        return readyCheckService.getMyMatchStateV2(1L).readyCheck().matchId();
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
            super(redisTemplate, serializer, properties);
        }

        @Override
        public com.back.domain.matching.queue.model.MatchSession markAcceptPending(
                QueueKey queueKey, List<WaitingUser> matchedUsers, LocalDateTime deadline) {
            throw new RuntimeException("mark failed");
        }
    }
}
