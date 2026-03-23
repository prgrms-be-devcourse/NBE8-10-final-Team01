package com.back.domain.matching.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.back.domain.battle.battleroom.dto.CreateRoomRequest;
import com.back.domain.battle.battleroom.dto.CreateRoomResponse;
import com.back.domain.battle.battleroom.service.BattleRoomService;
import com.back.domain.matching.queue.dto.QueueJoinRequest;
import com.back.domain.matching.queue.dto.QueueStatusResponse;
import com.back.domain.matching.queue.model.Difficulty;
import com.back.domain.matching.queue.model.QueueKey;
import com.back.domain.problem.pick.service.ProblemPickService;
import com.back.domain.problem.problem.enums.DifficultyLevel;

class MatchingQueueServiceTest {

    private final BattleRoomService battleRoomService = mock(BattleRoomService.class);
    private final ProblemPickService problemPickService = mock(ProblemPickService.class);
    private final MatchingQueueService matchingQueueService =
            new MatchingQueueService(battleRoomService, problemPickService);

    @BeforeEach
    void setUp() {
        when(problemPickService.pickProblemId(anyString(), any(DifficultyLevel.class), anyList()))
                .thenReturn(1L);
    }

    @Test
    @DisplayName("사용자는 카테고리와 난이도를 선택해 매칭 대기열에 참가할 수 있다")
    void joinQueue_success() {
        // given
        Long userId = 1L;
        QueueJoinRequest request = createRequest("Array", Difficulty.EASY);

        // when
        QueueStatusResponse response = matchingQueueService.joinQueue(userId, request);

        // then
        assertThat(response.getMessage()).isEqualTo("매칭 대기열에 참가했습니다.");
        assertThat(response.getCategory()).isEqualTo("ARRAY");
        assertThat(response.getDifficulty()).isEqualTo("EASY");
        assertThat(response.getWaitingCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("이미 대기열에 참가 중인 사용자는 중복 참가할 수 없다")
    void joinQueue_fail_whenAlreadyJoined() {
        // given
        Long userId = 1L;
        QueueJoinRequest request = createRequest("Array", Difficulty.EASY);

        matchingQueueService.joinQueue(userId, request);

        // when & then
        assertThatThrownBy(() -> matchingQueueService.joinQueue(userId, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("이미 매칭 대기열에 참가 중인 사용자입니다.");
    }

    @Test
    @DisplayName("대기열에 참가 중인 사용자는 큐 취소를 할 수 있다")
    void cancelQueue_success() {
        // given
        Long userId = 1L;
        QueueJoinRequest request = createRequest("Array", Difficulty.EASY);

        matchingQueueService.joinQueue(userId, request);

        // when
        QueueStatusResponse response = matchingQueueService.cancelQueue(userId);

        // then
        assertThat(response.getMessage()).isEqualTo("매칭 대기열에서 취소되었습니다.");
        assertThat(response.getCategory()).isEqualTo("ARRAY");
        assertThat(response.getDifficulty()).isEqualTo("EASY");
        assertThat(response.getWaitingCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("대기열에 참가 중이 아닌 사용자는 큐 취소를 할 수 없다")
    void cancelQueue_fail_whenUserNotInQueue() {
        // given
        Long userId = 99L;

        // when & then
        assertThatThrownBy(() -> matchingQueueService.cancelQueue(userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("현재 매칭 대기열에 참가 중이 아닙니다.");
    }

    @Test
    @DisplayName("마지막 한 명이 큐를 취소하면 해당 큐는 waitingQueues에서 제거된다")
    void cancelQueue_removesEmptyQueue() {
        // given
        Long userId = 1L;
        QueueJoinRequest request = createRequest("Array", Difficulty.EASY);
        QueueKey queueKey = new QueueKey("Array", Difficulty.EASY);

        matchingQueueService.joinQueue(userId, request);

        // 사전 확인
        assertThat(matchingQueueService.hasQueue(queueKey)).isTrue();

        // when
        matchingQueueService.cancelQueue(userId);

        // then
        assertThat(matchingQueueService.hasQueue(queueKey)).isFalse();
    }

    @Test
    @DisplayName("4번째 사용자가 참가하면 방 생성이 1회 호출되고 큐는 비워진다")
    void joinQueue_createsRoom_whenFourthUserJoins() {
        // given
        when(battleRoomService.createRoom(any(CreateRoomRequest.class)))
                .thenReturn(new CreateRoomResponse(100L, "WAITING"));

        // when
        matchingQueueService.joinQueue(1L, createRequest("Array", Difficulty.EASY));
        matchingQueueService.joinQueue(2L, createRequest("Array", Difficulty.EASY));
        matchingQueueService.joinQueue(3L, createRequest("Array", Difficulty.EASY));
        QueueStatusResponse fourthResponse =
                matchingQueueService.joinQueue(4L, createRequest("Array", Difficulty.EASY));

        // then
        verify(battleRoomService, times(1))
                .createRoom(argThat(req -> req.problemId().equals(1L)
                        && req.maxPlayers() == 4
                        && req.participantIds().size() == 4));
        verify(problemPickService, times(1))
                .pickProblemId(eq("ARRAY"), eq(DifficultyLevel.EASY), argThat(ids -> ids.size() == 4));
        assertThat(fourthResponse.getWaitingCount()).isEqualTo(0);
        assertThat(matchingQueueService.hasQueue(new QueueKey("Array", Difficulty.EASY)))
                .isFalse();
    }

    @Test
    @DisplayName("5번째 사용자는 다음 매칭을 위해 대기열에 남는다")
    void joinQueue_keepsFifthUserWaiting_afterRoomCreated() {
        // given
        when(battleRoomService.createRoom(any(CreateRoomRequest.class)))
                .thenReturn(new CreateRoomResponse(101L, "WAITING"));

        // when
        matchingQueueService.joinQueue(1L, createRequest("Array", Difficulty.EASY));
        matchingQueueService.joinQueue(2L, createRequest("Array", Difficulty.EASY));
        matchingQueueService.joinQueue(3L, createRequest("Array", Difficulty.EASY));
        matchingQueueService.joinQueue(4L, createRequest("Array", Difficulty.EASY));
        QueueStatusResponse fifthResponse = matchingQueueService.joinQueue(5L, createRequest("Array", Difficulty.EASY));

        // then
        verify(battleRoomService, times(1)).createRoom(any(CreateRoomRequest.class));
        assertThat(fifthResponse.getWaitingCount()).isEqualTo(1);
        assertThat(matchingQueueService.hasQueue(new QueueKey("Array", Difficulty.EASY)))
                .isTrue();
    }

    @Test
    @DisplayName("방 생성에 실패하면 추출된 4명은 큐로 원복된다")
    void joinQueue_rollsBackQueue_whenCreateRoomFails() {
        // given
        when(battleRoomService.createRoom(any(CreateRoomRequest.class)))
                .thenThrow(new RuntimeException("room create failed"));

        matchingQueueService.joinQueue(1L, createRequest("Array", Difficulty.EASY));
        matchingQueueService.joinQueue(2L, createRequest("Array", Difficulty.EASY));
        matchingQueueService.joinQueue(3L, createRequest("Array", Difficulty.EASY));

        // when & then (4번째에서 방 생성 시도 -> 실패)
        assertThatThrownBy(() -> matchingQueueService.joinQueue(4L, createRequest("Array", Difficulty.EASY)))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("room create failed");

        // 원복 확인: 1번 사용자를 취소하면 4명 중 1명만 빠져 3명이 남아야 한다.
        QueueStatusResponse cancelResponse = matchingQueueService.cancelQueue(1L);
        assertThat(cancelResponse.getWaitingCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("카테고리/난이도가 다른 큐끼리는 교차 매칭되지 않는다")
    void joinQueue_doesNotCrossMatch_betweenDifferentQueueKeys() {
        // when
        matchingQueueService.joinQueue(1L, createRequest("Array", Difficulty.EASY));
        matchingQueueService.joinQueue(2L, createRequest("Array", Difficulty.EASY));
        matchingQueueService.joinQueue(3L, createRequest("Array", Difficulty.EASY));
        matchingQueueService.joinQueue(4L, createRequest("Graph", Difficulty.EASY));

        // then
        verifyNoInteractions(battleRoomService);
        assertThat(matchingQueueService.hasQueue(new QueueKey("Array", Difficulty.EASY)))
                .isTrue();
        assertThat(matchingQueueService.hasQueue(new QueueKey("Graph", Difficulty.EASY)))
                .isTrue();
    }

    @Test
    @DisplayName("각 큐가 4명을 채우면 서로 독립적으로 방이 생성된다")
    void joinQueue_createsRoomsIndependently_perQueueKey() {
        // given
        when(battleRoomService.createRoom(any(CreateRoomRequest.class)))
                .thenReturn(new CreateRoomResponse(201L, "WAITING"))
                .thenReturn(new CreateRoomResponse(202L, "WAITING"));

        // Array + EASY 4명
        matchingQueueService.joinQueue(1L, createRequest("Array", Difficulty.EASY));
        matchingQueueService.joinQueue(2L, createRequest("Array", Difficulty.EASY));
        matchingQueueService.joinQueue(3L, createRequest("Array", Difficulty.EASY));
        matchingQueueService.joinQueue(4L, createRequest("Array", Difficulty.EASY));

        // Graph + EASY 4명
        matchingQueueService.joinQueue(11L, createRequest("Graph", Difficulty.EASY));
        matchingQueueService.joinQueue(12L, createRequest("Graph", Difficulty.EASY));
        matchingQueueService.joinQueue(13L, createRequest("Graph", Difficulty.EASY));
        matchingQueueService.joinQueue(14L, createRequest("Graph", Difficulty.EASY));

        // then
        verify(battleRoomService, times(2)).createRoom(any(CreateRoomRequest.class));
        assertThat(matchingQueueService.hasQueue(new QueueKey("Array", Difficulty.EASY)))
                .isFalse();
        assertThat(matchingQueueService.hasQueue(new QueueKey("Graph", Difficulty.EASY)))
                .isFalse();
    }

    private QueueJoinRequest createRequest(String category, Difficulty difficulty) {
        return new QueueJoinRequest(category, difficulty);
    }
}
