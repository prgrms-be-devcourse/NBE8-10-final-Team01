package com.back.domain.battle.result.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.back.domain.battle.result.dto.BattleResultResponse;
import com.back.domain.battle.result.dto.RoomListResponse;
import com.back.domain.battle.result.service.BattleResultService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/battle")
@RequiredArgsConstructor
public class BattleResultController {

    private final BattleResultService battleResultService;

    // 최종 결과 조회
    @GetMapping("/rooms/{roomId}/result")
    public BattleResultResponse getResult(@PathVariable Long roomId) {
        return battleResultService.getResult(roomId);
    }

    // 진행중인 방 목록 조회 (관전용)
    @GetMapping("/rooms")
    public List<RoomListResponse> getRoomList() {
        return battleResultService.getRoomList();
    }
}
