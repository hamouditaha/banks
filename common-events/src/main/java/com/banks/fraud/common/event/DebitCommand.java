package com.banks.fraud.common.event;

import java.math.BigDecimal;

/**
 * Saga step 1: instructs the account-service to reserve/debit funds from the source account.
 * Idempotent per {@code sagaId} - the account-service dedupes on this key via Redis.
 */
public record DebitCommand(
        String sagaId,
        String transactionId,
        String accountId,
        BigDecimal amount
) {
}
