package com.back.domain.battle.result.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import com.back.domain.battle.result.event.BattleSettlementRequestedEvent;

class BattleSettlementCoordinatorTest {

    private final BattleSettlementExecutor battleSettlementExecutor = mock(BattleSettlementExecutor.class);
    private final BattleSettlementCoordinator coordinator = new BattleSettlementCoordinator(battleSettlementExecutor);

    @Test
    void onRequested_delegatesToExecutor() {
        coordinator.onRequested(new BattleSettlementRequestedEvent(123L));

        verify(battleSettlementExecutor).settle(123L);
    }
}
