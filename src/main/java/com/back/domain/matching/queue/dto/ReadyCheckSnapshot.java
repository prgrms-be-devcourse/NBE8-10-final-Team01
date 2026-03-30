package com.back.domain.matching.queue.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ready-check 화면 전체에 필요한 정보 묶음
 *
 * MatchStateV2Response 안에 포함되어
 * 수락 수, 나의 수락 여부, 제한 시간, 참가자 목록을 한 번에 내려준다.
 */
public record ReadyCheckSnapshot(
        Long matchId,
        int acceptedCount,
        int requiredCount,
        boolean acceptedByMe,
        LocalDateTime deadline,
        List<ReadyParticipantSnapshot> participants) {}
