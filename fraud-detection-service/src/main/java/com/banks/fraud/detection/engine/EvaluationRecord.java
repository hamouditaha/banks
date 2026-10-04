package com.banks.fraud.detection.engine;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** One fraud decision as kept in the evaluation log, for review from the back-office UI. */
public record EvaluationRecord(
        String sagaId,
        String fromAccountId,
        String toAccountId,
        BigDecimal amount,
        boolean approved,
        int riskScore,
        List<String> triggeredRules,
        Instant evaluatedAt
) {
}
