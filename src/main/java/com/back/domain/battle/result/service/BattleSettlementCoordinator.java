package com.back.domain.battle.result.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.back.domain.battle.result.event.BattleSettlementRequestedEvent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class BattleSettlementCoordinator {

    private final BattleSettlementExecutor battleSettlementExecutor;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(BattleSettlementRequestedEvent event) {
        log.info("BattleSettlementCoordinator received roomId={}", event.roomId());

        battleSettlementExecutor.settle(event.roomId());
    }
}
