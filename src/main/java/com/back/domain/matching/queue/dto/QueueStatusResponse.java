package com.back.domain.matching.queue.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 매칭 시작 요청 결과를 프론트에 전달하는 응답 DTO
 *
 * 예를 들어 사용자가 매칭 시작 버튼을 눌렀을 때,
 * 백엔드는 대기열 등록 결과와 현재 대기 인원 수를 이 객체에 담아 응답한다.
 */
@Getter
@AllArgsConstructor
public class QueueStatusResponse {

    /**
     * 처리 결과 메시지
     * 예: "매칭 대기열에 참가했습니다."
     */
    private String message;

    /**
     * 사용자가 참가한 카테고리
     * 내부적으로는 정규화된 값이 들어갈 수 있다.
     * 예: "ARRAY"
     */
    private String category;

    /**
     * 사용자가 참가한 문제 난이도
     * 예: "EASY"
     */
    private String difficulty;

    /**
     * 해당 카테고리 + 난이도 큐에서 현재 대기 중인 인원 수
     */
    private int waitingCount;
}
