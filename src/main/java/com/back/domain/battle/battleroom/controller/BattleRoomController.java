package com.back.domain.battle.battleroom.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.back.domain.battle.battleroom.dto.CreateRoomRequest;
import com.back.domain.battle.battleroom.dto.CreateRoomResponse;
import com.back.domain.battle.battleroom.service.BattleRoomService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/battle/rooms")
@RequiredArgsConstructor
public class BattleRoomController {

    private final BattleRoomService battleRoomService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateRoomResponse createRoom(@RequestBody CreateRoomRequest request) {
        return battleRoomService.createRoom(request);
    }
}
