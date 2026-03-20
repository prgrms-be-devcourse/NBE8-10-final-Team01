package com.back.domain.matching.queue.model;

/**
 * 어떤 대기열(큐)에 들어가는지를 구분하는 키
 *
 * category + difficulty 조합으로 하나의 큐를 식별한다.
 */
public record QueueKey(String category, Difficulty difficulty) {

    public QueueKey {
        // 카테고리 공백 제거 + 대소문자 차이 줄이기
        category = category == null ? null : category.trim().toUpperCase();
    }
}
