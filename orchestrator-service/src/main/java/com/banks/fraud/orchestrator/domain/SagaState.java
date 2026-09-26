package com.banks.fraud.orchestrator.domain;

import com.banks.fraud.common.SagaStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent state of a single transfer saga, stored as JSON under {@code saga:{sagaId}}
 * in Redis. This is the orchestrator's source of truth for "where is this saga right now",
 * used both to decide the next command to issue and to guard against duplicate/out-of-order
 * Kafka reply delivery.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SagaState {
    private String sagaId;
    private String transactionId;
    private String fromAccountId;
    private String toAccountId;
    private BigDecimal amount;
    private SagaStatus status;
    private String reason;
    @Builder.Default
    private List<String> history = new ArrayList<>();
    private Instant createdAt;
    private Instant updatedAt;

    public void moveTo(SagaStatus newStatus, String note) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
        this.history.add(Instant.now() + " -> " + newStatus + (note != null ? " (" + note + ")" : ""));
    }
}
