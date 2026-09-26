package com.banks.fraud.common.event;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Saga step 2: asks the fraud-detection-service to evaluate the transfer before funds move.
 * {@code metadata} carries optional signals (deviceId, ipAddress, channel) the rule engine can use.
 */
public record FraudCheckCommand(
        String sagaId,
        String transactionId,
        String fromAccountId,
        String toAccountId,
        BigDecimal amount,
        Map<String, String> metadata
) {
}
