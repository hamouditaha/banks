package com.banks.fraud.account.dto;

import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record DepositRequest(@Positive BigDecimal amount) {
}
