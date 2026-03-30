package com.back.domain.matching.queue.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.back.domain.matching.queue.dto.MatchStateV2Response;
import com.back.domain.matching.queue.service.ReadyCheckService;
import com.back.domain.member.member.entity.Member;
import com.back.global.exception.ServiceException;
import com.back.global.rq.Rq;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v2/matches")
@RequiredArgsConstructor
/**
 * v2 ready-check / room 준비 상태 API
 *
 * matches/me는 queue 진행률이 아니라
 * "내 ready-check 세션이 지금 어떤 상태인가"를 보여주는 전용 endpoint다.
 */
public class MatchingStatusV2Controller {

    private final ReadyCheckService readyCheckService;
    private final Rq rq;

    @GetMapping("/me")
    public MatchStateV2Response getMyMatchState() {
        // queue 단계가 끝난 뒤 프론트는 이 endpoint로 ready-check / room 상태를 본다.
        return readyCheckService.getMyMatchStateV2(requireActorId());
    }

    @PostMapping("/{matchId}/accept")
    public MatchStateV2Response acceptMatch(@PathVariable Long matchId) {
        // 수락 요청은 현재 로그인 사용자 기준으로만 처리한다.
        return readyCheckService.acceptMatch(requireActorId(), matchId);
    }

    @PostMapping("/{matchId}/decline")
    public MatchStateV2Response declineMatch(@PathVariable Long matchId) {
        // 거절도 같은 방식으로 현재 로그인 사용자 기준으로 처리한다.
        return readyCheckService.declineMatch(requireActorId(), matchId);
    }

    private Long requireActorId() {
        Member actor = rq.getActor();

        if (actor == null) {
            throw new ServiceException("MEMBER_401", "로그인이 필요합니다.");
        }

        return actor.getId();
    }
}
