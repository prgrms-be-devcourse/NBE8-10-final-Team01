package com.back.domain.matching.queue.dto;

import com.back.domain.matching.queue.model.Difficulty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 매칭 시작 요청 DTO
 *
 * 예:
 * {
 *   "category": "Array",
 *   "difficulty": "EASY"
 * }
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class QueueJoinRequest {

    @NotBlank(message = "카테고리는 필수입니다.") private String category;

    @NotNull(message = "난이도는 필수입니다.") private Difficulty difficulty;
}
