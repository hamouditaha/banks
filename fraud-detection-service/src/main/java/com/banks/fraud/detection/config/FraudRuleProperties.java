package com.banks.fraud.detection.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "fraud.rules")
public record FraudRuleProperties(
        BigDecimal largeAmountThreshold,
        BigDecimal veryLargeAmountThreshold,
        int velocityWindowSeconds,
        int velocityMaxTransactions,
        int rejectScoreThreshold
) {
}
