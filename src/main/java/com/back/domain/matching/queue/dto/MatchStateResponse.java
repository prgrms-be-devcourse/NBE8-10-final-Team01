package com.back.domain.matching.queue.dto;

// 매칭 상태 조회 응답 DTO
public record MatchStateResponse(
        String status, // SEARCHING, MATCHED, IDLE
        Long roomId // 매칭 완료 전이면 null
        ) {}
