package com.banks.fraud.detection.engine;

import com.banks.fraud.common.event.FraudCheckCommand;
import com.banks.fraud.detection.config.FraudRuleProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Rule-based fraud engine. Each rule contributes a risk score; the transfer is
 * rejected once the accumulated score reaches {@code rejectScoreThreshold}, or
 * immediately if either party is blacklisted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FraudRuleEngine {

    private final BlacklistService blacklistService;
    private final VelocityService velocityService;
    private final FraudRuleProperties properties;

    public FraudEvaluation evaluate(FraudCheckCommand command) {
        List<String> triggeredRules = new ArrayList<>();
        int score = 0;

        if (blacklistService.isBlacklisted(command.fromAccountId()) || blacklistService.isBlacklisted(command.toAccountId())) {
            triggeredRules.add("BLACKLISTED_ACCOUNT");
            score = 100;
            return new FraudEvaluation(false, score, triggeredRules);
        }

        if (command.amount().compareTo(properties.veryLargeAmountThreshold()) >= 0) {
            triggeredRules.add("VERY_LARGE_AMOUNT");
            score += 70;
        } else if (command.amount().compareTo(properties.largeAmountThreshold()) >= 0) {
            triggeredRules.add("LARGE_AMOUNT");
            score += 40;
        }

        int recentTransactions = velocityService.recordAndCount(command.fromAccountId(), properties.velocityWindowSeconds());
        if (recentTransactions > properties.velocityMaxTransactions()) {
            triggeredRules.add("VELOCITY_LIMIT_EXCEEDED");
            score += 50;
        }

        boolean approved = score < properties.rejectScoreThreshold();
        log.info("Fraud evaluation for saga {}: score={}, approved={}, rules={}",
                command.sagaId(), score, approved, triggeredRules);
        return new FraudEvaluation(approved, score, triggeredRules);
    }
}
