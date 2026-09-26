package com.banks.fraud.account.service;

/**
 * Outcome of a debit/credit/refund attempt. Serialized to a compact "OK" / "FAIL:<reason>"
 * string so it can be cached against an idempotency key in Redis and replayed verbatim
 * if the same Kafka command is redelivered.
 */
public record OperationResult(boolean success, String reason) {

    public static OperationResult success() {
        return new OperationResult(true, null);
    }

    public static OperationResult failure(String reason) {
        return new OperationResult(false, reason);
    }

    public String toCacheValue() {
        return success ? "OK" : "FAIL:" + reason;
    }

    public static OperationResult fromCacheValue(String cached) {
        if ("OK".equals(cached)) {
            return success();
        }
        return failure(cached.substring("FAIL:".length()));
    }
}
