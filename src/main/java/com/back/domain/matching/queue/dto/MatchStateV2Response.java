package com.back.domain.matching.queue.dto;

/**
 * v2 matches/me 최종 응답 DTO
 *
 * 프론트는 이 응답 하나로
 * ready-check를 보여줄지, 방 이동이 가능한지, 종료 메시지를 띄울지를 판단한다.
 */
public record MatchStateV2Response(
        MatchStatus status, ReadyCheckSnapshot readyCheck, RoomSnapshot room, String message) {}
