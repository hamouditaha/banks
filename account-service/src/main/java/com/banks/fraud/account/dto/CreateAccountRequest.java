package com.banks.fraud.account.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record CreateAccountRequest(
        @NotBlank String ownerName,
        @NotBlank String currency,
        @PositiveOrZero BigDecimal openingBalance
) {
}
