package com.back.domain.matching.queue.dto;

import com.back.domain.matching.queue.model.ReadyDecision;

/**
 * ready-check 화면에서 참가자 한 명을 표현하는 응답 조각
 *
 * 프론트는 이 배열을 이용해
 * 누가 수락했고, 누가 아직 대기 중이고, 누가 거절했는지를 바로 그린다.
 */
public record ReadyParticipantSnapshot(Long userId, String nickname, ReadyDecision decision) {}
