package com.banks.fraud.common.event;

import com.banks.fraud.common.SagaStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Final event published by the orchestrator once a saga reaches a terminal state
 * (COMPLETED, FAILED or COMPENSATED). Consumed by notification-service.
 */
public record TransactionOutcomeEvent(
        String sagaId,
        String transactionId,
        String fromAccountId,
        String toAccountId,
        BigDecimal amount,
        SagaStatus status,
        String reason,
        Instant occurredAt
) {
}
