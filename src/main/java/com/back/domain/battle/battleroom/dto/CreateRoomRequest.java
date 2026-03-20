package com.back.domain.battle.battleroom.dto;

import java.util.List;

/**
 * problemId, participantIds, maxPlayers를 받는 record
 */
public record CreateRoomRequest(Long problemId, List<Long> participantIds, int maxPlayers) {}
