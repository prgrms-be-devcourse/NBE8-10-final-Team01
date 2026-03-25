package com.back.domain.matching.queue.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.back.domain.matching.queue.dto.MatchStateResponse;
import com.back.domain.matching.queue.service.MatchingQueueService;
import com.back.domain.member.member.entity.Member;
import com.back.global.exception.ServiceException;
import com.back.global.rq.Rq;

import lombok.RequiredArgsConstructor;

// 매칭 상태 전용 조회 컨트롤러
@RestController
@RequestMapping("/api/v1/matches")
@RequiredArgsConstructor
public class MatchingStatusController {

    private final MatchingQueueService matchingQueueService;
    private final Rq rq;

    // 현재 사용자의 매칭 상태 조회
    @GetMapping("/me")
    public MatchStateResponse getMyMatchState() {
        Member actor = rq.getActor();

        if (actor == null) {
            throw new ServiceException("MEMBER_401", "로그인이 필요합니다.");
        }

        return matchingQueueService.getMyMatchState(actor.getId());
    }
}
