package com.back.domain.matching.queue.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ReadyCheckExpirationScheduler {

    private final ReadyCheckService readyCheckService;

    // ready-check 만료는 전역 스케줄러가 주기적으로 확인해 WebSocket 이벤트로 밀어준다.
    @Scheduled(fixedDelay = 2000)
    public void expireTimedOutMatches() {
        readyCheckService.expireTimedOutMatches();
    }
}
