package com.back.global.websocket.dto;

/**
 * 참여자가 코드 변경 시 서버로 전송하는 메시지
 * TODO: Security 연동 후 userId 제거하고 Principal에서 추출
 */
public record CodeUpdateMessage(Long userId, String code) {}
