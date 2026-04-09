package com.back.domain.battle.result.service;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.back.global.websocket.BattleTimerStore;

class BattleSettlementExecutorTest {

    private final BattleResultService battleResultService = mock(BattleResultService.class);
    private final BattleTimerStore battleTimerStore = mock(BattleTimerStore.class);
    private final BattleSettlementExecutor executor =
            new BattleSettlementExecutor(battleResultService, battleTimerStore);

    @Test
    void settle_cancelsTimerBeforeDelegatingToBattleResultService() {
        executor.settle(123L);

        InOrder inOrder = inOrder(battleTimerStore, battleResultService);
        inOrder.verify(battleTimerStore).cancel(123L);
        inOrder.verify(battleResultService).settle(123L);
    }
}
