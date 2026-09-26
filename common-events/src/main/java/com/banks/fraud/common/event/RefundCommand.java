package com.banks.fraud.common.event;

import java.math.BigDecimal;

/**
 * Compensating action: reverses a previously successful debit when a later saga step
 * (fraud check or credit) fails.
 */
public record RefundCommand(
        String sagaId,
        String transactionId,
        String accountId,
        BigDecimal amount,
        String reason
) {
}
