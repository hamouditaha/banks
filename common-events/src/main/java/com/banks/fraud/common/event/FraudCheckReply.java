package com.banks.fraud.common.event;

import java.util.List;

public record FraudCheckReply(
        String sagaId,
        String transactionId,
        boolean approved,
        int riskScore,
        List<String> triggeredRules
) {
}
