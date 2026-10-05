package com.banks.fraud.account.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OperationResultTest {

    @Test
    void successRoundTripsThroughCacheValue() {
        OperationResult restored = OperationResult.fromCacheValue(OperationResult.ok().toCacheValue());

        assertThat(restored.success()).isTrue();
        assertThat(restored.reason()).isNull();
    }

    @Test
    void failureKeepsItsReason() {
        OperationResult restored = OperationResult.fromCacheValue(
                OperationResult.failed("INSUFFICIENT_FUNDS").toCacheValue());

        assertThat(restored.success()).isFalse();
        assertThat(restored.reason()).isEqualTo("INSUFFICIENT_FUNDS");
    }
}
