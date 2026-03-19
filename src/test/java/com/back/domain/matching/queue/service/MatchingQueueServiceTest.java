package com.back.domain.matching.queue.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.back.domain.matching.queue.dto.QueueJoinRequest;
import com.back.domain.matching.queue.dto.QueueStatusResponse;
import com.back.domain.matching.queue.model.Difficulty;

class MatchingQueueServiceTest {

    private final MatchingQueueService matchingQueueService = new MatchingQueueService();

    @Test
    @DisplayName("사용자는 카테고리와 난이도를 선택해 매칭 대기열에 참가할 수 있다")
    void joinQueue_success() throws Exception {
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
    void joinQueue_fail_whenAlreadyJoined() throws Exception {
        // given
        Long userId = 1L;
        QueueJoinRequest request = createRequest("Array", Difficulty.EASY);

        matchingQueueService.joinQueue(userId, request);

        // when & then
        assertThatThrownBy(() -> matchingQueueService.joinQueue(userId, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("이미 매칭 대기열에 참가 중인 사용자입니다.");
    }

    /**
     * QueueJoinRequest는 현재 기본 생성자만 있고 setter가 없으므로
     * 테스트에서는 reflection으로 필드 값을 넣는다.
     */
    private QueueJoinRequest createRequest(String category, Difficulty difficulty) throws Exception {
        QueueJoinRequest request = new QueueJoinRequest();

        Field categoryField = QueueJoinRequest.class.getDeclaredField("category");
        categoryField.setAccessible(true);
        categoryField.set(request, category);

        Field difficultyField = QueueJoinRequest.class.getDeclaredField("difficulty");
        difficultyField.setAccessible(true);
        difficultyField.set(request, difficulty);

        return request;
    }
}
