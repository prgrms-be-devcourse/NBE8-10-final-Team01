package com.back.domain.matching.queue.dto;

/**
 * matching WebSocket 이벤트 공통 envelope
 *
 * queue 변화와 ready-check 시작 handoff가 같은 채널 규약을 쓰도록
 * type + payload 조합으로 감싸서 보낸다.
 */
public record MatchingEventResponse(MatchingEventType type, QueueStateV2Response queue, MatchStateV2Response match) {}
