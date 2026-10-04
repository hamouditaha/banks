package com.banks.fraud.detection.kafka;

import com.banks.fraud.common.KafkaTopics;
import com.banks.fraud.common.event.FraudCheckCommand;
import com.banks.fraud.common.event.FraudCheckReply;
import com.banks.fraud.detection.engine.EvaluationLogService;
import com.banks.fraud.detection.engine.EvaluationRecord;
import com.banks.fraud.detection.engine.FraudEvaluation;
import com.banks.fraud.detection.engine.FraudRuleEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Slf4j
@Component
@RequiredArgsConstructor
public class FraudCheckListener {

    private final FraudRuleEngine fraudRuleEngine;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final EvaluationLogService evaluationLogService;

    @KafkaListener(topics = KafkaTopics.FRAUD_CHECK_COMMAND, groupId = "fraud-detection-service")
    public void onFraudCheckCommand(FraudCheckCommand command) {
        log.info("Evaluating transfer for saga {}: {} -> {} amount {}",
                command.sagaId(), command.fromAccountId(), command.toAccountId(), command.amount());
        FraudEvaluation evaluation = fraudRuleEngine.evaluate(command);
        evaluationLogService.record(new EvaluationRecord(
                command.sagaId(), command.fromAccountId(), command.toAccountId(), command.amount(),
                evaluation.approved(), evaluation.riskScore(), evaluation.triggeredRules(), Instant.now()));
        kafkaTemplate.send(KafkaTopics.FRAUD_CHECK_REPLY, command.sagaId(), new FraudCheckReply(
                command.sagaId(), command.transactionId(), evaluation.approved(),
                evaluation.riskScore(), evaluation.triggeredRules()));
    }
}
