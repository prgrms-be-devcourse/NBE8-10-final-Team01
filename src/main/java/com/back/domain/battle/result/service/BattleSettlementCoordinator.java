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

    /**
     * {@link BattleSettlementRequestedEvent} 는 반드시 AFTER_COMMIT 에서만 소비한다.
     * 이 규칙이 깨지면 커밋 이전 정산 실행으로 트랜잭션 경계가 꼬일 수 있다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onRequested(BattleSettlementRequestedEvent event) {
        log.info("BattleSettlementCoordinator received roomId={}", event.roomId());

        battleSettlementExecutor.settle(event.roomId());
    }
}
