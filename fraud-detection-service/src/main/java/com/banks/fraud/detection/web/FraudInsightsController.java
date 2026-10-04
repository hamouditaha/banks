package com.banks.fraud.detection.web;

import com.banks.fraud.detection.config.FraudRuleProperties;
import com.banks.fraud.detection.engine.EvaluationLogService;
import com.banks.fraud.detection.engine.EvaluationRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only view of the active rule configuration and the recent decisions it produced. */
@RestController
@RequestMapping("/api/fraud")
@RequiredArgsConstructor
public class FraudInsightsController {

    private final FraudRuleProperties properties;
    private final EvaluationLogService evaluationLogService;

    @GetMapping("/rules")
    public FraudRuleProperties rules() {
        return properties;
    }

    @GetMapping("/evaluations")
    public List<EvaluationRecord> evaluations(@RequestParam(defaultValue = "100") int limit) {
        return evaluationLogService.recent(Math.min(limit, 500));
    }
}
