package com.banks.fraud.common.event;

import java.math.BigDecimal;

public record CreditReply(
        String sagaId,
        String transactionId,
        String accountId,
        BigDecimal amount,
        boolean success,
        String reason
) {
}
