package com.banks.fraud.detection.engine;

import java.util.List;

public record FraudEvaluation(boolean approved, int riskScore, List<String> triggeredRules) {
}
