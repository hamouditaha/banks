package com.banks.fraud.account.service;

import com.banks.fraud.account.domain.Account;
import com.banks.fraud.account.domain.AccountStatus;
import com.banks.fraud.account.dto.AccountResponse;
import com.banks.fraud.account.dto.CreateAccountRequest;
import com.banks.fraud.account.exception.AccountNotFoundException;
import com.banks.fraud.account.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccountService {

    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);
    private static final Duration LOCK_WAIT = Duration.ofSeconds(5);
    private static final Duration LOCK_LEASE = Duration.ofSeconds(10);

    private final AccountRepository accountRepository;
    private final RedissonClient redissonClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final RedisScript<Long> conditionalDebitScript;

    public AccountResponse createAccount(CreateAccountRequest request) {
        Account account = Account.builder()
                .id(UUID.randomUUID().toString())
                .ownerName(request.ownerName())
                .currency(request.currency().toUpperCase())
                .status(AccountStatus.ACTIVE)
                .createdAt(Instant.now())
                .build();
        long openingBalance = toMinorUnits(request.openingBalance() == null ? BigDecimal.ZERO : request.openingBalance());
        accountRepository.save(account, openingBalance);
        log.info("Created account {} for {} with opening balance {}", account.getId(), account.getOwnerName(), request.openingBalance());
        return toResponse(account);
    }

    public AccountResponse getAccount(String accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        return toResponse(account);
    }

    public List<AccountResponse> listAccounts() {
        return accountRepository.findAll().stream().map(this::toResponse).toList();
    }

    /** Manual funding endpoint for demo purposes - not part of the saga. */
    public AccountResponse deposit(String accountId, BigDecimal amount) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        accountRepository.creditMinorUnits(accountId, toMinorUnits(amount));
        return toResponse(account);
    }

    /**
     * Saga step: attempts to reserve/debit funds from {@code accountId}.
     * Idempotent on {@code idempotencyKey} (typically {@code sagaId + ":debit"}) so a
     * redelivered Kafka command never debits twice.
     */
    public OperationResult debit(String accountId, BigDecimal amount, String idempotencyKey) {
        return withAccountLock(accountId, idempotencyKey, () -> {
            var accountOpt = accountRepository.findById(accountId);
            if (accountOpt.isEmpty()) {
                return OperationResult.failure("ACCOUNT_NOT_FOUND");
            }
            if (accountOpt.get().getStatus() != AccountStatus.ACTIVE) {
                return OperationResult.failure("ACCOUNT_NOT_ACTIVE");
            }
            long minorAmount = toMinorUnits(amount);
            Long result = stringRedisTemplate.execute(
                    conditionalDebitScript,
                    List.of(accountRepository.balanceKey(accountId)),
                    String.valueOf(minorAmount));
            if (result != null && result == 1L) {
                log.info("Debited {} minor units from account {} (saga={})", minorAmount, accountId, idempotencyKey);
                return OperationResult.success();
            }
            log.warn("Debit rejected for account {} - insufficient funds (saga={})", accountId, idempotencyKey);
            return OperationResult.failure("INSUFFICIENT_FUNDS");
        });
    }

    /**
     * Saga step: credits funds into {@code accountId}. Also used to apply the
     * compensating refund when a later saga step fails.
     */
    public OperationResult credit(String accountId, BigDecimal amount, String idempotencyKey) {
        return withAccountLock(accountId, idempotencyKey, () -> {
            var accountOpt = accountRepository.findById(accountId);
            if (accountOpt.isEmpty()) {
                return OperationResult.failure("ACCOUNT_NOT_FOUND");
            }
            long minorAmount = toMinorUnits(amount);
            accountRepository.creditMinorUnits(accountId, minorAmount);
            log.info("Credited {} minor units to account {} (saga={})", minorAmount, accountId, idempotencyKey);
            return OperationResult.success();
        });
    }

    /**
     * Runs {@code operation} under a Redisson distributed lock scoped to the account,
     * with the result cached against {@code idempotencyKey} so a duplicate Kafka
     * delivery replays the cached outcome instead of re-applying the balance change.
     */
    private OperationResult withAccountLock(String accountId, String idempotencyKey, java.util.function.Supplier<OperationResult> operation) {
        String idemKey = "idem:" + idempotencyKey;
        String cached = stringRedisTemplate.opsForValue().get(idemKey);
        if (cached != null) {
            log.info("Replaying cached result for idempotency key {}", idempotencyKey);
            return OperationResult.fromCacheValue(cached);
        }

        RLock lock = redissonClient.getLock("lock:account:" + accountId);
        boolean locked;
        try {
            locked = lock.tryLock(LOCK_WAIT.toSeconds(), LOCK_LEASE.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return OperationResult.failure("LOCK_INTERRUPTED");
        }
        if (!locked) {
            return OperationResult.failure("LOCK_TIMEOUT");
        }
        try {
            // Re-check cache: another thread may have completed the same operation
            // while we were waiting for the lock.
            String cachedAfterLock = stringRedisTemplate.opsForValue().get(idemKey);
            if (cachedAfterLock != null) {
                return OperationResult.fromCacheValue(cachedAfterLock);
            }
            OperationResult result = operation.get();
            stringRedisTemplate.opsForValue().set(idemKey, result.toCacheValue(), IDEMPOTENCY_TTL);
            return result;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private long toMinorUnits(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    private BigDecimal toMajorUnits(long minorUnits) {
        return BigDecimal.valueOf(minorUnits, 2);
    }

    private AccountResponse toResponse(Account account) {
        long balance = accountRepository.getBalanceMinorUnits(account.getId());
        return new AccountResponse(
                account.getId(),
                account.getOwnerName(),
                account.getCurrency(),
                account.getStatus(),
                toMajorUnits(balance),
                account.getCreatedAt());
    }
}
