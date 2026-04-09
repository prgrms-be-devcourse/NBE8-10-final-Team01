package com.back.domain.battle.result.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.back.domain.battle.result.event.BattleSettlementRequestedEvent;

class BattleSettlementCoordinatorTest {

    private final BattleSettlementExecutor battleSettlementExecutor = mock(BattleSettlementExecutor.class);
    private final BattleSettlementCoordinator coordinator = new BattleSettlementCoordinator(battleSettlementExecutor);

    @Test
    void onRequested_delegatesToExecutor() {
        coordinator.onRequested(new BattleSettlementRequestedEvent(123L));

        verify(battleSettlementExecutor).settle(123L);
    }

    @Test
    void onRequested_isBoundToAfterCommitPhase() throws NoSuchMethodException {
        Method method =
                BattleSettlementCoordinator.class.getMethod("onRequested", BattleSettlementRequestedEvent.class);

        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        assertNotNull(annotation);
        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
    }
}
