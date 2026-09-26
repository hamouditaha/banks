package com.banks.fraud.account.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Account metadata as persisted in Redis under key {@code account:{id}} (JSON string).
 * The balance itself is NOT stored here - it lives in a separate Redis counter key
 * ({@code account:{id}:balance}, minor units) so it can be updated atomically
 * (INCRBY / a Lua script) without read-modify-write races on the whole object.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Account {
    private String id;
    private String ownerName;
    private String currency;
    private AccountStatus status;
    private Instant createdAt;
}
