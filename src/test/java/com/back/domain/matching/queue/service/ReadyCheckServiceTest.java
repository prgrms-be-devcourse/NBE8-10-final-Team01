package com.back.domain.matching.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
import com.back.domain.matching.queue.store.InMemoryMatchStateStore;
import com.back.domain.matching.queue.store.MatchStateStore;

class ReadyCheckServiceTest {

    private final BattleRoomService battleRoomService = mock(BattleRoomService.class);
    private final QueueProblemPicker queueProblemPicker = mock(QueueProblemPicker.class);
    private final MatchStateStore matchStateStore = new InMemoryMatchStateStore();

    private final ReadyCheckService readyCheckService =
            new ReadyCheckService(battleRoomService, queueProblemPicker, matchStateStore);

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
        MatchStateV2Response response = readyCheckService.acceptMatch(4L, matchId);

        assertThat(response.status()).isEqualTo(MatchStatus.ROOM_READY);
        assertThat(response.room()).isNotNull();
        assertThat(response.room().roomId()).isEqualTo(100L);
        assertThat(response.readyCheck().acceptedCount()).isEqualTo(4);
        verify(battleRoomService, times(1)).createRoom(any(CreateRoomRequest.class));
    }

    @Test
    @DisplayName("한 명이라도 거절하면 세션 전체가 CANCELLED로 종료된다")
    void declineMatch_returnsCancelled() {
        joinUser(1L);
        joinUser(2L);
        joinUser(3L);
        joinUser(4L);

        Long matchId = readyCheckService.getMyMatchStateV2(1L).readyCheck().matchId();

        MatchStateV2Response response = readyCheckService.declineMatch(2L, matchId);

        assertThat(response.status()).isEqualTo(MatchStatus.CANCELLED);
        assertThat(response.message()).isEqualTo("다른 참가자가 매칭을 거절했습니다.");
        assertThat(response.readyCheck().participants()).anySatisfy(participant -> {
            if (participant.userId().equals(2L)) {
                assertThat(participant.decision().name()).isEqualTo("DECLINED");
            }
        });
    }

    @Test
    @DisplayName("deadline이 지난 ready-check 세션은 EXPIRED로 조회된다")
    void getMyMatchStateV2_returnsExpired_whenDeadlinePassed() {
        QueueKey queueKey = new QueueKey("Array", Difficulty.EASY);
        List<WaitingUser> users = List.of(
                new WaitingUser(1L, "m1", queueKey),
                new WaitingUser(2L, "m2", queueKey),
                new WaitingUser(3L, "m3", queueKey),
                new WaitingUser(4L, "m4", queueKey));

        matchStateStore.markAcceptPending(queueKey, users, LocalDateTime.now().minusSeconds(1));

        MatchStateV2Response response = readyCheckService.getMyMatchStateV2(1L);

        assertThat(response.status()).isEqualTo(MatchStatus.EXPIRED);
        assertThat(response.message()).isEqualTo("수락 시간이 만료되었습니다.");
    }

    @Test
    @DisplayName("방 생성이 실패하면 CANCELLED 상태와 실패 메시지를 반환한다")
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
        MatchStateV2Response response = readyCheckService.acceptMatch(4L, matchId);

        assertThat(response.status()).isEqualTo(MatchStatus.CANCELLED);
        assertThat(response.message()).isEqualTo("방 생성에 실패해 매칭이 취소되었습니다.");
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

    private QueueJoinRequest createRequest(String category, Difficulty difficulty) {
        return new QueueJoinRequest(category, difficulty);
    }

    private QueueStatusResponse joinUser(Long userId) {
        return readyCheckService.joinQueueV2(userId, "m" + userId, createRequest("Array", Difficulty.EASY));
    }
}
