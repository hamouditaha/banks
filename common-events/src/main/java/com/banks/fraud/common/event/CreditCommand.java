package com.banks.fraud.common.event;

import java.math.BigDecimal;

/**
 * Saga step 3: instructs the account-service to credit funds to the destination account.
 */
public record CreditCommand(
        String sagaId,
        String transactionId,
        String accountId,
        BigDecimal amount
) {
}
