package com.back.domain.battle.result.event;

/**
 * Battle 정산 요청 이벤트.
 *
 * 이 이벤트는 트랜잭션 내부에서 발행되지만, 소비는 반드시
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)} 로만 처리한다.
 * 일반 {@code @EventListener} / {@code ApplicationListener} 로 소비하면
 * 커밋 이전에 정산이 실행될 수 있으므로 금지한다.
 */
public record BattleSettlementRequestedEvent(Long roomId) {}
