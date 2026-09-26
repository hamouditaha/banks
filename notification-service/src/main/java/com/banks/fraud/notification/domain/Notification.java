package com.banks.fraud.notification.domain;

import com.banks.fraud.common.SagaStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification {
    private String id;
    private String accountId;
    private String sagaId;
    private BigDecimal amount;
    private SagaStatus status;
    private String message;
    private Instant occurredAt;
}
