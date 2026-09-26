package com.banks.fraud.account.dto;

import com.banks.fraud.account.domain.AccountStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountResponse(
        String id,
        String ownerName,
        String currency,
        AccountStatus status,
        BigDecimal balance,
        Instant createdAt
) {
}
