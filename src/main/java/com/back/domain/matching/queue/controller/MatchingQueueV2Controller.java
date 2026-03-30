package com.back.domain.matching.queue.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.back.domain.matching.queue.dto.QueueJoinRequest;
import com.back.domain.matching.queue.dto.QueueStateV2Response;
import com.back.domain.matching.queue.dto.QueueStatusResponse;
import com.back.domain.matching.queue.service.ReadyCheckService;
import com.back.domain.member.member.entity.Member;
import com.back.global.exception.ServiceException;
import com.back.global.rq.Rq;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v2/queue")
@RequiredArgsConstructor
/**
 * v2 큐 관련 API
 *
 * queue/me는 SEARCHING UI 전용으로 유지한다.
 * 즉 "지금 몇 명 찼는지"를 보여주는 역할만 하고,
 * ready-check나 room 상태는 여기서 다루지 않는다.
 */
public class MatchingQueueV2Controller {

    private final ReadyCheckService readyCheckService;
    private final Rq rq;

    @GetMapping("/me")
    public QueueStateV2Response getMyQueueState() {
        // 프론트는 이 endpoint를 폴링하면서 1/4, 2/4 같은 SEARCHING UI를 그린다.
        return readyCheckService.getMyQueueStateV2(requireActorId());
    }

    @PostMapping("/join")
    public QueueStatusResponse joinQueue(@Valid @RequestBody QueueJoinRequest request) {
        // queue/join은 큐 참가만 담당하고,
        // ready-check 시작 여부 판단은 서비스에서 처리한다.
        return readyCheckService.joinQueueV2(requireActorId(), request);
    }

    @DeleteMapping("/cancel")
    public QueueStatusResponse cancelQueue() {
        // 아직 SEARCHING 단계인 사용자만 queue/cancel의 대상이 된다.
        return readyCheckService.cancelQueueV2(requireActorId());
    }

    private Long requireActorId() {
        Member actor = rq.getActor();

        if (actor == null) {
            throw new ServiceException("MEMBER_401", "로그인이 필요합니다.");
        }

        return actor.getId();
    }
}
