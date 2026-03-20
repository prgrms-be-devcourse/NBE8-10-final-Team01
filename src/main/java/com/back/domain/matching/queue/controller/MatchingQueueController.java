package com.back.domain.matching.queue.controller;

import org.springframework.web.bind.annotation.*;

import com.back.domain.matching.queue.dto.QueueJoinRequest;
import com.back.domain.matching.queue.dto.QueueStatusResponse;
import com.back.domain.matching.queue.service.MatchingQueueService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/queue")
@RequiredArgsConstructor
public class MatchingQueueController {

    private final MatchingQueueService matchingQueueService;

    /**
     * 매칭 시작 요청
     *
     * 지금은 테스트를 위해 userId를 요청 파라미터로 받는다.
     * 나중에는 JWT에서 userId를 꺼내도록 바꿀 수 있다.
     */
    @PostMapping("/join")
    public QueueStatusResponse joinQueue(@RequestParam Long userId, @Valid @RequestBody QueueJoinRequest request) {
        return matchingQueueService.joinQueue(userId, request);
    }

    @DeleteMapping("/cancel")
    public QueueStatusResponse cancelQueue(@RequestParam Long userId) {
        return matchingQueueService.cancelQueue(userId);
    }
}
