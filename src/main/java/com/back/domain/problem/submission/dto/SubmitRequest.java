package com.back.domain.problem.submission.dto;

/**
 * 임시: Security 미연동 상태에서 memberId를 body로 받음
 * TODO: Security 연동 후 @AuthenticationPrincipal로 교체
 */
public record SubmitRequest(Long roomId, Long memberId, String code, String language) {}
