package com.back.domain.battle.result.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.back.global.websocket.BattleTimerStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class BattleSettlementExecutor {

    private final BattleResultService battleResultService;
    private final BattleTimerStore battleTimerStore;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void settle(Long roomId) {
        try {
            log.info("BattleSettlementExecutor settle start roomId={}", roomId);
            battleTimerStore.cancel(roomId);
            battleResultService.settle(roomId);
            log.info("BattleSettlementExecutor settle end roomId={}", roomId);
        } catch (Exception e) {
            log.error("BattleSettlementExecutor settle failed roomId={}", roomId, e);
            throw e;
        }
    }
}
