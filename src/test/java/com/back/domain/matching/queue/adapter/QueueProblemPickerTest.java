package com.back.domain.matching.queue.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.back.domain.matching.queue.model.Difficulty;
import com.back.domain.matching.queue.model.QueueKey;
import com.back.domain.problem.pick.service.ProblemPickService;
import com.back.domain.problem.problem.enums.DifficultyLevel;

class QueueProblemPickerTest {

    private final ProblemPickService problemPickService = mock(ProblemPickService.class);
    private final QueueProblemPicker queueProblemPicker = new QueueProblemPicker(problemPickService);

    @Test
    @DisplayName("QueueKey와 participantIds를 받아 ProblemPickService 호출 결과를 반환한다")
    void pick_returnsProblemId_whenInputIsValid() {
        // given
        QueueKey queueKey = new QueueKey("Array", Difficulty.EASY);
        List<Long> participantIds = List.of(1L, 2L, 3L, 4L);

        when(problemPickService.pickProblemId("ARRAY", DifficultyLevel.EASY, participantIds))
                .thenReturn(7L);

        // when
        Long problemId = queueProblemPicker.pick(queueKey, participantIds);

        // then
        assertThat(problemId).isEqualTo(7L);
        verify(problemPickService).pickProblemId(eq("ARRAY"), eq(DifficultyLevel.EASY), eq(participantIds));
    }

    @Test
    @DisplayName("queueKey가 null이면 예외를 던진다")
    void pick_throws_whenQueueKeyIsNull() {
        assertThatThrownBy(() -> queueProblemPicker.pick(null, List.of(1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("queueKey는 필수입니다.");
    }
}
