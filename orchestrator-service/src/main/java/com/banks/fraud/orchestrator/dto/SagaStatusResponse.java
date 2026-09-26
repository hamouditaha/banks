package com.banks.fraud.orchestrator.dto;

import com.banks.fraud.common.SagaStatus;
import com.banks.fraud.orchestrator.domain.SagaState;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record SagaStatusResponse(
        String sagaId,
        String transactionId,
        String fromAccountId,
        String toAccountId,
        BigDecimal amount,
        SagaStatus status,
        String reason,
        List<String> history,
        Instant createdAt,
        Instant updatedAt
) {
    public static SagaStatusResponse from(SagaState saga) {
        return new SagaStatusResponse(
                saga.getSagaId(), saga.getTransactionId(), saga.getFromAccountId(), saga.getToAccountId(),
                saga.getAmount(), saga.getStatus(), saga.getReason(), saga.getHistory(),
                saga.getCreatedAt(), saga.getUpdatedAt());
    }
}
