package com.back.domain.matching.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.back.domain.matching.queue.dto.QueueJoinRequest;
import com.back.domain.matching.queue.dto.QueueStatusResponse;
import com.back.domain.matching.queue.model.Difficulty;
import com.back.domain.matching.queue.model.QueueKey;

class MatchingQueueServiceTest {

    private final MatchingQueueService matchingQueueService = new MatchingQueueService();

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

    private QueueJoinRequest createRequest(String category, Difficulty difficulty) {
        return new QueueJoinRequest(category, difficulty);
    }
}
