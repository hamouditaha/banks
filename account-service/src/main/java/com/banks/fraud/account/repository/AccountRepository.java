package com.banks.fraud.account.repository;

import com.banks.fraud.account.domain.Account;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Redis-backed persistence for account metadata and balances.
 *
 * Key layout:
 *  - account:{id}          -> JSON blob of {@link Account} (metadata only)
 *  - account:{id}:balance  -> long, balance in minor units (cents), updated atomically
 *  - accounts:index        -> Set of all account ids, for listing
 */
@Repository
@RequiredArgsConstructor
public class AccountRepository {

    private static final String ACCOUNT_KEY_PREFIX = "account:";
    private static final String BALANCE_KEY_SUFFIX = ":balance";
    private static final String INDEX_KEY = "accounts:index";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public Account save(Account account, long openingBalanceMinorUnits) {
        writeMetadata(account);
        redisTemplate.opsForValue().setIfAbsent(balanceKey(account.getId()), String.valueOf(openingBalanceMinorUnits));
        redisTemplate.opsForSet().add(INDEX_KEY, account.getId());
        return account;
    }

    public void updateMetadata(Account account) {
        writeMetadata(account);
    }

    @SneakyThrows(JsonProcessingException.class)
    private void writeMetadata(Account account) {
        redisTemplate.opsForValue().set(metadataKey(account.getId()), objectMapper.writeValueAsString(account));
    }

    public Optional<Account> findById(String accountId) {
        String json = redisTemplate.opsForValue().get(metadataKey(accountId));
        if (json == null) {
            return Optional.empty();
        }
        return Optional.of(readAccount(json));
    }

    public boolean existsById(String accountId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(metadataKey(accountId)));
    }

    public List<Account> findAll() {
        Set<String> ids = redisTemplate.opsForSet().members(INDEX_KEY);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return ids.stream()
                .map(this::findById)
                .flatMap(Optional::stream)
                .toList();
    }

    public long getBalanceMinorUnits(String accountId) {
        String raw = redisTemplate.opsForValue().get(balanceKey(accountId));
        return raw == null ? 0L : Long.parseLong(raw);
    }

    public long creditMinorUnits(String accountId, long amountMinorUnits) {
        Long result = redisTemplate.opsForValue().increment(balanceKey(accountId), amountMinorUnits);
        return result == null ? 0L : result;
    }

    public String metadataKey(String accountId) {
        return ACCOUNT_KEY_PREFIX + accountId;
    }

    public String balanceKey(String accountId) {
        return ACCOUNT_KEY_PREFIX + accountId + BALANCE_KEY_SUFFIX;
    }

    @SneakyThrows(JsonProcessingException.class)
    private Account readAccount(String json) {
        return objectMapper.readValue(json, Account.class);
    }
}
