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
import com.back.domain.matching.queue.model.ReadyDecision;
import com.back.domain.matching.queue.model.WaitingUser;
import com.back.domain.matching.queue.store.MatchStateStore;
import com.back.domain.matching.queue.store.MatchingStoreProperties;
import com.back.global.exception.ServiceException;
import com.fasterxml.jackson.databind.json.JsonMapper;

class RedisMatchStateStoreTest {

    private FakeStringRedisTemplate redisTemplate;
    private MatchingRedisSerializer serializer;
    private RedisMatchStateStore store;

    @BeforeEach
    void setUp() {
        redisTemplate = new FakeStringRedisTemplate();
        serializer = new MatchingRedisSerializer(
                JsonMapper.builder().findAndAddModules().build());

        MatchingStoreProperties properties = new MatchingStoreProperties();
        properties.setType(MatchingStoreProperties.StoreType.REDIS);

        store = new RedisMatchStateStore(redisTemplate, serializer, properties);
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
    @DisplayName("markAcceptPending 은 match 문서와 userMatch 인덱스를 함께 저장하고 userQueue 를 삭제한다")
    void markAcceptPending_storesReadyCheckStateInRedis() {
        QueueKey queueKey = queueKey();
        enqueueUsers(queueKey, 4);
        List<WaitingUser> matchedUsers = store.pollMatchCandidates(queueKey, 4);

        MatchSession matchSession =
                store.markAcceptPending(queueKey, matchedUsers, LocalDateTime.of(2026, 4, 6, 12, 30, 0));

        assertThat(matchSession.status()).isEqualTo(MatchSessionStatus.ACCEPT_PENDING);
        assertThat(redisTemplate.opsForValue().get(MatchingRedisKeys.match(matchSession.matchId())))
                .isNotNull();
        assertThat(redisTemplate.opsForValue().get(MatchingRedisKeys.userMatch(1L)))
                .isEqualTo(String.valueOf(matchSession.matchId()));
        assertThat(redisTemplate.opsForValue().get(MatchingRedisKeys.userQueue(1L)))
                .isNull();
    }

    @Test
    @DisplayName("findMatchSessionByUserId 는 Redis ready-check 세션을 읽고 stale link 를 정리한다")
    void findMatchSessionByUserId_readsSessionAndCleansStaleLink() {
        MatchSession matchSession = createAcceptPendingMatch(LocalDateTime.of(2026, 4, 6, 12, 30, 0));

        MatchSession found = store.findMatchSessionByUserId(1L);

        assertThat(found).isNotNull();
        assertThat(found.matchId()).isEqualTo(matchSession.matchId());

        redisTemplate.opsForValue().set(MatchingRedisKeys.userMatch(99L), "999");
        assertThat(store.findMatchSessionByUserId(99L)).isNull();
        assertThat(redisTemplate.opsForValue().get(MatchingRedisKeys.userMatch(99L)))
                .isNull();
    }

    @Test
    @DisplayName("accept 는 decision 을 갱신하고 중복 accept 는 현재 상태를 유지한다")
    void accept_updatesDecisionAndKeepsDuplicateAccept() {
        MatchSession matchSession = createAcceptPendingMatch(LocalDateTime.now().plusSeconds(30));

        MatchSession accepted = store.accept(matchSession.matchId(), 1L);
        MatchSession duplicated = store.accept(matchSession.matchId(), 1L);

        assertThat(accepted.decisionOf(1L)).isEqualTo(ReadyDecision.ACCEPTED);
        assertThat(duplicated.decisionOf(1L)).isEqualTo(ReadyDecision.ACCEPTED);
        assertThat(duplicated.acceptedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("decline 은 CANCELLED 로 전이한다")
    void decline_returnsCancelled() {
        MatchSession matchSession = createAcceptPendingMatch(LocalDateTime.now().plusSeconds(30));

        MatchSession declined = store.decline(matchSession.matchId(), 2L);

        assertThat(declined.status()).isEqualTo(MatchSessionStatus.CANCELLED);
        assertThat(declined.decisionOf(2L)).isEqualTo(ReadyDecision.DECLINED);
    }

    @Test
    @DisplayName("deadline 이 지난 세션에서 accept 하면 EXPIRED 를 우선 반영한다")
    void accept_returnsExpiredWhenDeadlinePassed() {
        MatchSession matchSession = createAcceptPendingMatch(LocalDateTime.now().minusSeconds(1));

        MatchSession expired = store.accept(matchSession.matchId(), 1L);

        assertThat(expired.status()).isEqualTo(MatchSessionStatus.EXPIRED);
    }

    @Test
    @DisplayName("tryBeginRoomCreation 은 마지막 accept 경쟁에서도 한 번만 선점한다")
    void tryBeginRoomCreation_acquiresOnlyOnce() {
        MatchSession matchSession = createAcceptPendingMatch(LocalDateTime.now().plusSeconds(30));
        store.accept(matchSession.matchId(), 1L);
        store.accept(matchSession.matchId(), 2L);
        store.accept(matchSession.matchId(), 3L);
        store.accept(matchSession.matchId(), 4L);

        MatchStateStore.RoomCreationAttempt firstAttempt = store.tryBeginRoomCreation(matchSession.matchId());
        MatchStateStore.RoomCreationAttempt secondAttempt = store.tryBeginRoomCreation(matchSession.matchId());

        assertThat(firstAttempt.acquired()).isTrue();
        assertThat(firstAttempt.matchSession().status()).isEqualTo(MatchSessionStatus.ROOM_CREATING);
        assertThat(secondAttempt.acquired()).isFalse();
        assertThat(secondAttempt.matchSession().status()).isEqualTo(MatchSessionStatus.ROOM_CREATING);
    }

    @Test
    @DisplayName("markRoomReady 와 clearMatchedRoom 은 참가자 참조를 기준으로 세션을 정리한다")
    void markRoomReadyAndClearMatchedRoom_cleanupByReferences() {
        MatchSession matchSession = createAcceptPendingMatch(LocalDateTime.now().plusSeconds(30));
        store.accept(matchSession.matchId(), 1L);
        store.accept(matchSession.matchId(), 2L);
        store.accept(matchSession.matchId(), 3L);
        store.accept(matchSession.matchId(), 4L);
        store.tryBeginRoomCreation(matchSession.matchId());

        MatchSession roomReady = store.markRoomReady(matchSession.matchId(), 100L);
        store.clearMatchedRoom(1L, 100L);

        assertThat(roomReady.status()).isEqualTo(MatchSessionStatus.ROOM_READY);
        assertThat(store.findMatchSessionByUserId(1L)).isNull();
        assertThat(store.findMatchSessionByUserId(2L)).isNotNull();

        store.clearMatchedRoom(2L, 100L);
        store.clearMatchedRoom(3L, 100L);
        store.clearMatchedRoom(4L, 100L);

        assertThat(redisTemplate.opsForValue().get(MatchingRedisKeys.match(matchSession.matchId())))
                .isNull();
    }

    @Test
    @DisplayName("clearTerminalMatch 는 match 문서와 userMatch 인덱스를 함께 정리한다")
    void clearTerminalMatch_removesMatchAndUserLinks() {
        MatchSession matchSession = createAcceptPendingMatch(LocalDateTime.now().plusSeconds(30));
        store.decline(matchSession.matchId(), 1L);

        store.clearTerminalMatch(matchSession.matchId());

        assertThat(redisTemplate.opsForValue().get(MatchingRedisKeys.match(matchSession.matchId())))
                .isNull();
        assertThat(redisTemplate.opsForValue().get(MatchingRedisKeys.userMatch(1L)))
                .isNull();
        assertThat(redisTemplate.opsForValue().get(MatchingRedisKeys.userMatch(4L)))
                .isNull();
    }

    @Test
    @DisplayName("findExpiredAcceptPendingMatchIds 는 임시 match key scan 기준으로 만료 세션만 반환한다")
    void findExpiredAcceptPendingMatchIds_returnsExpiredMatches() {
        MatchSession expired = createAcceptPendingMatch(LocalDateTime.now().minusSeconds(1));
        MatchSession active = createAcceptPendingMatch(LocalDateTime.now().plusSeconds(30), 11L);

        List<Long> expiredMatchIds = store.findExpiredAcceptPendingMatchIds(LocalDateTime.now());

        assertThat(expiredMatchIds).contains(expired.matchId());
        assertThat(expiredMatchIds).doesNotContain(active.matchId());
    }

    private MatchSession createAcceptPendingMatch(LocalDateTime deadline) {
        return createAcceptPendingMatch(deadline, 1L);
    }

    private MatchSession createAcceptPendingMatch(LocalDateTime deadline, long startUserId) {
        QueueKey queueKey = queueKey();
        List<WaitingUser> matchedUsers = List.of(
                new WaitingUser(startUserId, "m" + startUserId, queueKey),
                new WaitingUser(startUserId + 1, "m" + (startUserId + 1), queueKey),
                new WaitingUser(startUserId + 2, "m" + (startUserId + 2), queueKey),
                new WaitingUser(startUserId + 3, "m" + (startUserId + 3), queueKey));

        return store.markAcceptPending(queueKey, matchedUsers, deadline);
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
