package com.banks.fraud.detection.engine;

import com.banks.fraud.common.event.FraudCheckCommand;
import com.banks.fraud.detection.config.FraudRuleProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FraudRuleEngineTest {

    private BlacklistService blacklistService;
    private VelocityService velocityService;
    private FraudRuleEngine engine;

    @BeforeEach
    void setUp() {
        blacklistService = mock(BlacklistService.class);
        velocityService = mock(VelocityService.class);
        FraudRuleProperties properties = new FraudRuleProperties(
                new BigDecimal("10000"), new BigDecimal("50000"), 60, 5, 70);
        engine = new FraudRuleEngine(blacklistService, velocityService, properties);
        when(velocityService.recordAndCount(anyString(), anyInt())).thenReturn(1);
    }

    @Test
    void approvesSmallTransfer() {
        FraudEvaluation result = engine.evaluate(command("100"));

        assertThat(result.approved()).isTrue();
        assertThat(result.riskScore()).isZero();
        assertThat(result.triggeredRules()).isEmpty();
    }

    @Test
    void rejectsImmediatelyWhenReceiverIsBlacklisted() {
        when(blacklistService.isBlacklisted("B")).thenReturn(true);

        FraudEvaluation result = engine.evaluate(command("10"));

        assertThat(result.approved()).isFalse();
        assertThat(result.riskScore()).isEqualTo(100);
        assertThat(result.triggeredRules()).containsExactly("BLACKLISTED_ACCOUNT");
        verify(velocityService, never()).recordAndCount(anyString(), anyInt());
    }

    @Test
    void largeAmountAloneStaysBelowThreshold() {
        FraudEvaluation result = engine.evaluate(command("10000"));

        assertThat(result.approved()).isTrue();
        assertThat(result.riskScore()).isEqualTo(40);
        assertThat(result.triggeredRules()).containsExactly("LARGE_AMOUNT");
    }

    @Test
    void veryLargeAmountIsRejected() {
        FraudEvaluation result = engine.evaluate(command("50000"));

        assertThat(result.approved()).isFalse();
        assertThat(result.riskScore()).isEqualTo(70);
        assertThat(result.triggeredRules()).containsExactly("VERY_LARGE_AMOUNT");
    }

    @Test
    void largeAmountCombinedWithVelocityIsRejected() {
        when(velocityService.recordAndCount("A", 60)).thenReturn(6);

        FraudEvaluation result = engine.evaluate(command("15000"));

        assertThat(result.approved()).isFalse();
        assertThat(result.riskScore()).isEqualTo(90);
        assertThat(result.triggeredRules()).containsExactly("LARGE_AMOUNT", "VELOCITY_LIMIT_EXCEEDED");
    }

    private FraudCheckCommand command(String amount) {
        return new FraudCheckCommand("saga-1", "tx-1", "A", "B", new BigDecimal(amount), Map.of());
    }
}
