package com.banks.fraud.account.dto;

import com.banks.fraud.account.domain.AccountStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateStatusRequest(@NotNull AccountStatus status) {
}
