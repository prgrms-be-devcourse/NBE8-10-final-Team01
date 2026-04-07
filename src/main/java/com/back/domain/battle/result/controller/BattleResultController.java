package com.back.domain.battle.result.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.back.domain.battle.result.dto.ActiveRoomResponse;
import com.back.domain.battle.result.dto.BattleResultResponse;
import com.back.domain.battle.result.dto.RoomListResponse;
import com.back.domain.battle.result.dto.UncheckedResultResponse;
import com.back.domain.battle.result.service.BattleResultService;
import com.back.global.rq.Rq;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/battle")
@RequiredArgsConstructor
public class BattleResultController {

    private final BattleResultService battleResultService;
    private final Rq rq;

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

    // 현재 유저가 참여 중인 배틀방 조회 (관전 시도 시 사전 확인용)
    @GetMapping("/rooms/me/active")
    public ActiveRoomResponse getActiveRoom() {
        Long memberId = rq.getActor().getId();
        return battleResultService.getActiveRoom(memberId);
    }

    // 미확인 배틀 결과 조회 — 로그인/홈 진입 시 호출해 결과 화면으로 유도
    @GetMapping("/result/unchecked")
    public UncheckedResultResponse getUncheckedResult() {
        Long memberId = rq.getActor().getId();
        return battleResultService.getUncheckedResult(memberId);
    }

    // 결과 확인 처리 — 결과 화면 진입 시 호출
    @PostMapping("/result/{roomId}/check")
    public void checkResult(@PathVariable Long roomId) {
        Long memberId = rq.getActor().getId();
        battleResultService.checkResult(roomId, memberId);
    }
}
