package com.back.domain.battle.battleroom.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.back.domain.battle.battleroom.dto.CreateRoomRequest;
import com.back.domain.battle.battleroom.dto.CreateRoomResponse;
import com.back.domain.battle.battleroom.dto.JoinRoomResponse;
import com.back.domain.battle.battleroom.dto.RoomResponse;
import com.back.domain.battle.battleroom.service.BattleRoomService;
import com.back.domain.matching.queue.service.MatchingQueueService;
import com.back.global.rq.Rq;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/battle/rooms")
@RequiredArgsConstructor
public class BattleRoomController {

    private final BattleRoomService battleRoomService;
    private final MatchingQueueService matchingQueueService;
    private final Rq rq;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateRoomResponse createRoom(@RequestBody CreateRoomRequest request) {
        return battleRoomService.createRoom(request);
    }

    @PostMapping("/{roomId}/join")
    public JoinRoomResponse joinRoom(@PathVariable Long roomId) {
        Long memberId = rq.getActor().getId();
        JoinRoomResponse response = battleRoomService.joinRoom(roomId, memberId);

        // 이 유저는 이제 실제로 방 입장까지 끝났으므로 매칭 결과 정리
        matchingQueueService.clearMatchedRoom(memberId, roomId);
        return response;
    }

    @GetMapping("/{roomId}")
    public RoomResponse getRoomInfo(@PathVariable Long roomId) {
        return battleRoomService.getRoomInfo(roomId);
    }
}
