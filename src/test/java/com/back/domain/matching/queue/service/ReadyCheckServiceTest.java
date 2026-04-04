package com.back.domain.matching.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import com.back.domain.matching.queue.model.MatchSessionStatus;
import com.back.domain.matching.queue.model.QueueKey;
import com.back.domain.matching.queue.model.WaitingUser;
import com.back.domain.matching.queue.store.InMemoryMatchStateStore;
import com.back.domain.matching.queue.store.MatchStateStore;

class ReadyCheckServiceTest {

    private final BattleRoomService battleRoomService = mock(BattleRoomService.class);
    private final QueueProblemPicker queueProblemPicker = mock(QueueProblemPicker.class);
    private final InMemoryMatchStateStore matchStateStore = new InMemoryMatchStateStore();
    private final MatchingEventPublisher matchingEventPublisher = mock(MatchingEventPublisher.class);

    private final ReadyCheckService readyCheckService =
            new ReadyCheckService(battleRoomService, queueProblemPicker, matchStateStore, matchingEventPublisher);

    @BeforeEach
    void setUp() {
        when(queueProblemPicker.pick(any(QueueKey.class), anyList())).thenReturn(1L);
    }

    @Test
    @DisplayName("v2 queue/me는 SEARCHING 동안 waitingCount와 requiredCount를 반환한다")
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
    @DisplayName("queue cancel 후에는 감소한 waitingCount를 queue topic 이벤트로 발행한다")
    void cancelQueueV2_publishesQueueStateChanged() {
        joinUser(1L);
        joinUser(2L);

        QueueStatusResponse response = readyCheckService.cancelQueueV2(2L);

        assertThat(response.getWaitingCount()).isEqualTo(1);
        verify(matchingEventPublisher, times(2)).publishQueueStateChanged(any(QueueKey.class), eq(1));
        verify(matchingEventPublisher, times(1)).publishQueueStateChanged(any(QueueKey.class), eq(2));
    }

    @Test
    @DisplayName("4명이 모이면 v2 matches/me는 ACCEPT_PENDING을 반환한다")
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
        assertThat(response.readyCheck().acceptedByMe()).isFalse();
        assertThat(response.readyCheck().participants()).hasSize(4);
        assertThat(response.readyCheck().participants())
                .extracting(participant -> participant.nickname())
                .containsExactly("m1", "m2", "m3", "m4");
        verify(matchingEventPublisher, never()).publishQueueStateChanged(any(QueueKey.class), eq(4));
    }

    @Test
    @DisplayName("4번째 join 시 matched 4명에게 READY_CHECK_STARTED를 발행한다")
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
            assertThat(response.room()).isNull();
            assertThat(response.message()).isNull();
        });
    }

    @Test
    @DisplayName("일반 accept 시 matched 4명에게 READY_DECISION_CHANGED를 발행한다")
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
    @DisplayName("전원 수락이 완료되면 ROOM_READY와 roomId를 반환한다")
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
        verify(battleRoomService, times(1)).createRoom(any(CreateRoomRequest.class));
        verify(matchingEventPublisher, times(4)).publishRoomReady(any(), any());
    }

    @Test
    @DisplayName("한 명이라도 거절하면 세션 전체가 CANCELLED로 종료된다")
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
        assertThat(response.readyCheck().participants()).anySatisfy(participant -> {
            if (participant.userId().equals(2L)) {
                assertThat(participant.decision().name()).isEqualTo("DECLINED");
            }
        });
        assertThat(readyCheckService.getMyMatchStateV2(1L).status()).isEqualTo(MatchStatus.IDLE);
        verify(matchingEventPublisher, times(4)).publishMatchCancelled(any(), any());
    }

    @Test
    @DisplayName("만료 스윕은 deadline 지난 ready-check 세션에 MATCH_EXPIRED를 발행하고 즉시 정리한다")
    void expireTimedOutMatches_publishesExpiredAndClearsSession() {
        QueueKey queueKey = new QueueKey("Array", Difficulty.EASY);
        List<WaitingUser> users = List.of(
                new WaitingUser(1L, "m1", queueKey),
                new WaitingUser(2L, "m2", queueKey),
                new WaitingUser(3L, "m3", queueKey),
                new WaitingUser(4L, "m4", queueKey));

        matchStateStore.markAcceptPending(queueKey, users, LocalDateTime.now().minusSeconds(1));
        clearInvocations(matchingEventPublisher);

        readyCheckService.expireTimedOutMatches();

        assertThat(readyCheckService.getMyMatchStateV2(1L).status()).isEqualTo(MatchStatus.IDLE);
        verify(matchingEventPublisher, times(4)).publishMatchExpired(any(), any());
    }

    @Test
    @DisplayName("이미 CANCELLED 된 세션은 만료 스윕에서 다시 EXPIRED 처리하지 않는다")
    void expireTimedOutMatches_skipsCancelledSession() {
        Long matchId = createAcceptPendingMatch();
        clearInvocations(matchingEventPublisher);

        readyCheckService.declineMatch(1L, matchId);
        clearInvocations(matchingEventPublisher);

        readyCheckService.expireTimedOutMatches();

        verify(matchingEventPublisher, never()).publishMatchExpired(any(), any());
    }

    @Test
    @DisplayName("방 생성에 실패하면 CANCELLED 상태와 실패 메시지를 반환한다")
    void acceptMatch_returnsCancelled_whenCreateRoomFails() {
        when(battleRoomService.createRoom(any(CreateRoomRequest.class)))
                .thenThrow(new RuntimeException("room create failed"));

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

        assertThat(response.status()).isEqualTo(MatchStatus.CANCELLED);
        assertThat(response.message()).isNotNull();
        assertThat(readyCheckService.getMyMatchStateV2(1L).status()).isEqualTo(MatchStatus.IDLE);
        verify(matchingEventPublisher, times(4)).publishMatchCancelled(any(), any());
    }

    @Test
    @DisplayName("ROOM_READY 이후 한 명만 입장하면 그 사용자만 IDLE로 빠지고 나머지는 유지된다")
    void clearMatchedRoom_removesOnlyEnteredUser_inV2Flow() {
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
        readyCheckService.acceptMatch(4L, matchId);

        readyCheckService.clearMatchedRoom(1L, 100L);

        assertThat(readyCheckService.getMyMatchStateV2(1L).status()).isEqualTo(MatchStatus.IDLE);
        assertThat(readyCheckService.getMyMatchStateV2(2L).status()).isEqualTo(MatchStatus.ROOM_READY);
        assertThat(readyCheckService.getMyMatchStateV2(3L).status()).isEqualTo(MatchStatus.ROOM_READY);
        assertThat(readyCheckService.getMyMatchStateV2(4L).status()).isEqualTo(MatchStatus.ROOM_READY);
    }

    @Test
    @DisplayName("마지막 accept가 동시에 들어와도 room은 한 번만 생성된다")
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
            assertThat(finalState.room().roomId()).isEqualTo(100L);
        } finally {
            executorService.shutdownNow();
        }
    }

    @Test
    @DisplayName("ROOM_CREATING은 matches/me에서 ACCEPT_PENDING으로만 보인다")
    void getMyMatchStateV2_hidesRoomCreatingState() {
        QueueKey queueKey = new QueueKey("Array", Difficulty.EASY);
        List<WaitingUser> users = List.of(
                new WaitingUser(1L, "m1", queueKey),
                new WaitingUser(2L, "m2", queueKey),
                new WaitingUser(3L, "m3", queueKey),
                new WaitingUser(4L, "m4", queueKey));

        Long matchId = matchStateStore
                .markAcceptPending(queueKey, users, LocalDateTime.now().plusSeconds(30))
                .matchId();

        matchStateStore.accept(matchId, 1L);
        matchStateStore.accept(matchId, 2L);
        matchStateStore.accept(matchId, 3L);
        matchStateStore.accept(matchId, 4L);
        MatchStateStore.RoomCreationAttempt roomCreationAttempt = matchStateStore.tryBeginRoomCreation(matchId);

        MatchStateV2Response response = readyCheckService.getMyMatchStateV2(1L);

        assertThat(roomCreationAttempt.acquired()).isTrue();
        assertThat(roomCreationAttempt.matchSession().status()).isEqualTo(MatchSessionStatus.ROOM_CREATING);
        assertThat(response.status()).isEqualTo(MatchStatus.ACCEPT_PENDING);
        assertThat(response.room()).isNull();
        assertThat(response.readyCheck()).isNotNull();
    }

    @Test
    @DisplayName("이미 ROOM_READY인 세션에 추가 accept가 와도 room은 다시 생성되지 않는다")
    void acceptMatch_doesNotCreateRoomAgain_whenAlreadyRoomReady() {
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
        readyCheckService.acceptMatch(4L, matchId);
        MatchStateV2Response response = readyCheckService.acceptMatch(4L, matchId);

        assertThat(response.status()).isEqualTo(MatchStatus.ROOM_READY);
        assertThat(response.room()).isNotNull();
        verify(battleRoomService, times(1)).createRoom(any(CreateRoomRequest.class));
    }

    @Test
    @DisplayName("ready-check 세션 생성 실패 시 rollback 후 queue waitingCount 복구 이벤트를 발행한다")
    void joinQueueV2_publishesRecoveredQueueEvent_whenMarkAcceptPendingFails() {
        MatchStateStore failingStore = mock(MatchStateStore.class);
        MatchingEventPublisher failingPublisher = mock(MatchingEventPublisher.class);
        ReadyCheckService failingService =
                new ReadyCheckService(battleRoomService, queueProblemPicker, failingStore, failingPublisher);
        QueueKey queueKey = new QueueKey("Array", Difficulty.EASY);
        List<WaitingUser> users = List.of(
                new WaitingUser(1L, "m1", queueKey),
                new WaitingUser(2L, "m2", queueKey),
                new WaitingUser(3L, "m3", queueKey),
                new WaitingUser(4L, "m4", queueKey));

        when(failingStore.enqueue(1L, "m1", queueKey)).thenReturn(4);
        when(failingStore.pollMatchCandidates(queueKey, 4)).thenReturn(users);
        when(failingStore.markAcceptPending(any(QueueKey.class), anyList(), any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("mark failed"));
        when(failingStore.getWaitingCount(queueKey)).thenReturn(4);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> failingService.joinQueueV2(1L, "m1", createRequest("Array", Difficulty.EASY)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("mark failed");

        verify(failingStore).rollbackPolledUsers(queueKey, users);
        verify(failingPublisher).publishQueueStateChanged(queueKey, 4);
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
}
