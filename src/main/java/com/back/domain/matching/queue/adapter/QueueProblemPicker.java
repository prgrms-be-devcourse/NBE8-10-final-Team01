package com.back.domain.matching.queue.adapter;

import java.util.List;

import org.springframework.stereotype.Component;

import com.back.domain.matching.queue.model.Difficulty;
import com.back.domain.matching.queue.model.QueueKey;
import com.back.domain.problem.pick.service.ProblemPickService;
import com.back.domain.problem.problem.enums.DifficultyLevel;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class QueueProblemPicker {

    private final ProblemPickService problemPickService;

    /**
     * 매칭 큐 모델을 출제 도메인 입력으로 변환해 문제 선정 서비스를 호출한다.
     */
    public Long pick(QueueKey queueKey, List<Long> participantIds) {
        if (queueKey == null) {
            throw new IllegalArgumentException("queueKey는 필수입니다.");
        }
        return problemPickService.pickProblemId(
                queueKey.category(), toDifficultyLevel(queueKey.difficulty()), participantIds);
    }

    private DifficultyLevel toDifficultyLevel(Difficulty difficulty) {
        if (difficulty == null) {
            throw new IllegalStateException("큐 난이도 정보가 없습니다.");
        }

        // valueOf 문자열 매핑 대신 명시적 매핑으로 런타임 이름 불일치 위험을 제거한다.
        return switch (difficulty) {
            case EASY -> DifficultyLevel.EASY;
            case MEDIUM -> DifficultyLevel.MEDIUM;
            case HARD -> DifficultyLevel.HARD;
        };
    }
}
