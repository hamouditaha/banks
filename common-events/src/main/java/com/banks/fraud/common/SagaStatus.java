package com.banks.fraud.common;

/**
 * Lifecycle states of a transfer SAGA instance, persisted in Redis by the orchestrator.
 */
public enum SagaStatus {
    STARTED,
    DEBIT_PENDING,
    DEBIT_FAILED,
    FRAUD_CHECK_PENDING,
    FRAUD_REJECTED,
    CREDIT_PENDING,
    CREDIT_FAILED,
    COMPLETED,
    COMPENSATING,
    COMPENSATED,
    FAILED
}
