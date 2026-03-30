package com.back.domain.matching.queue.dto;

/**
 * ROOM_READY 상태에서만 필요한 roomId 조각
 *
 * 프론트는 이 값을 받으면 기존 battle room 입장 API로 이동한다.
 */
public record RoomSnapshot(Long roomId) {}
