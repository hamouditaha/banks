package com.banks.fraud.orchestrator.service;

import com.banks.fraud.common.KafkaTopics;
import com.banks.fraud.common.SagaStatus;
import com.banks.fraud.common.event.CreditCommand;
import com.banks.fraud.common.event.CreditReply;
import com.banks.fraud.common.event.DebitReply;
import com.banks.fraud.common.event.FraudCheckCommand;
import com.banks.fraud.common.event.FraudCheckReply;
import com.banks.fraud.common.event.RefundCommand;
import com.banks.fraud.common.event.RefundReply;
import com.banks.fraud.common.event.TransactionOutcomeEvent;
import com.banks.fraud.orchestrator.domain.SagaState;
import com.banks.fraud.orchestrator.repository.SagaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Drives the transfer SAGA forward one step at a time as replies arrive from the
 * participant services (account-service, fraud-detection-service). Kafka partitions
 * by sagaId (used as the message key), so replies for a given saga are processed
 * in order by a single consumer thread - no extra locking is required to serialize
 * the state machine for one saga instance.
 *
 * Flow: DEBIT -> FRAUD_CHECK -> CREDIT -> COMPLETED
 * Compensation: a fraud rejection or a failed credit triggers a REFUND of the debited
 * source account, moving the saga to COMPENSATED (or FAILED if the refund itself fails).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SagaOrchestrator {

    private final SagaRepository sagaRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = KafkaTopics.DEBIT_REPLY, groupId = "orchestrator-service")
    public void onDebitReply(DebitReply reply) {
        Optional<SagaState> maybeSaga = loadForExpectedStatus(reply.sagaId(), SagaStatus.DEBIT_PENDING);
        if (maybeSaga.isEmpty()) {
            return;
        }
        SagaState saga = maybeSaga.get();

        if (reply.success()) {
            saga.moveTo(SagaStatus.FRAUD_CHECK_PENDING, "debit ok, requesting fraud check");
            sagaRepository.save(saga);
            kafkaTemplate.send(KafkaTopics.FRAUD_CHECK_COMMAND, saga.getSagaId(), new FraudCheckCommand(
                    saga.getSagaId(), saga.getTransactionId(), saga.getFromAccountId(), saga.getToAccountId(),
                    saga.getAmount(), Map.of()));
        } else {
            saga.setReason(reply.reason());
            saga.moveTo(SagaStatus.FAILED, "debit failed: " + reply.reason());
            sagaRepository.save(saga);
            publishOutcome(saga);
        }
    }

    @KafkaListener(topics = KafkaTopics.FRAUD_CHECK_REPLY, groupId = "orchestrator-service")
    public void onFraudCheckReply(FraudCheckReply reply) {
        Optional<SagaState> maybeSaga = loadForExpectedStatus(reply.sagaId(), SagaStatus.FRAUD_CHECK_PENDING);
        if (maybeSaga.isEmpty()) {
            return;
        }
        SagaState saga = maybeSaga.get();

        if (reply.approved()) {
            saga.moveTo(SagaStatus.CREDIT_PENDING, "fraud check passed (score=" + reply.riskScore() + ")");
            sagaRepository.save(saga);
            kafkaTemplate.send(KafkaTopics.CREDIT_COMMAND, saga.getSagaId(), new CreditCommand(
                    saga.getSagaId(), saga.getTransactionId(), saga.getToAccountId(), saga.getAmount()));
        } else {
            String reason = "fraud rejected (score=" + reply.riskScore() + ", rules=" + reply.triggeredRules() + ")";
            saga.setReason(reason);
            saga.moveTo(SagaStatus.FRAUD_REJECTED, reason);
            saga.moveTo(SagaStatus.COMPENSATING, "refunding source account");
            sagaRepository.save(saga);
            kafkaTemplate.send(KafkaTopics.REFUND_COMMAND, saga.getSagaId(), new RefundCommand(
                    saga.getSagaId(), saga.getTransactionId(), saga.getFromAccountId(), saga.getAmount(), reason));
        }
    }

    @KafkaListener(topics = KafkaTopics.CREDIT_REPLY, groupId = "orchestrator-service")
    public void onCreditReply(CreditReply reply) {
        Optional<SagaState> maybeSaga = loadForExpectedStatus(reply.sagaId(), SagaStatus.CREDIT_PENDING);
        if (maybeSaga.isEmpty()) {
            return;
        }
        SagaState saga = maybeSaga.get();

        if (reply.success()) {
            saga.moveTo(SagaStatus.COMPLETED, "credit ok");
            sagaRepository.save(saga);
            publishOutcome(saga);
        } else {
            String reason = "credit failed: " + reply.reason();
            saga.setReason(reason);
            saga.moveTo(SagaStatus.CREDIT_FAILED, reason);
            saga.moveTo(SagaStatus.COMPENSATING, "refunding source account");
            sagaRepository.save(saga);
            kafkaTemplate.send(KafkaTopics.REFUND_COMMAND, saga.getSagaId(), new RefundCommand(
                    saga.getSagaId(), saga.getTransactionId(), saga.getFromAccountId(), saga.getAmount(), reason));
        }
    }

    @KafkaListener(topics = KafkaTopics.REFUND_REPLY, groupId = "orchestrator-service")
    public void onRefundReply(RefundReply reply) {
        Optional<SagaState> maybeSaga = loadForExpectedStatus(reply.sagaId(), SagaStatus.COMPENSATING);
        if (maybeSaga.isEmpty()) {
            return;
        }
        SagaState saga = maybeSaga.get();

        if (reply.success()) {
            saga.moveTo(SagaStatus.COMPENSATED, "refund applied");
        } else {
            // The compensating action itself failed - this needs operator attention,
            // it cannot be auto-resolved further by the orchestrator.
            saga.setReason(saga.getReason() + " | REFUND FAILED: " + reply.reason());
            saga.moveTo(SagaStatus.FAILED, "refund failed: " + reply.reason());
        }
        sagaRepository.save(saga);
        publishOutcome(saga);
    }

    private Optional<SagaState> loadForExpectedStatus(String sagaId, SagaStatus expected) {
        Optional<SagaState> maybeSaga = sagaRepository.findById(sagaId);
        if (maybeSaga.isEmpty()) {
            log.warn("Received reply for unknown saga {}", sagaId);
            return Optional.empty();
        }
        SagaState saga = maybeSaga.get();
        if (saga.getStatus() != expected) {
            log.info("Ignoring reply for saga {} - expected status {} but was {} (likely a duplicate delivery)",
                    sagaId, expected, saga.getStatus());
            return Optional.empty();
        }
        return Optional.of(saga);
    }

    private void publishOutcome(SagaState saga) {
        kafkaTemplate.send(KafkaTopics.TRANSACTION_OUTCOME, saga.getSagaId(), new TransactionOutcomeEvent(
                saga.getSagaId(), saga.getTransactionId(), saga.getFromAccountId(), saga.getToAccountId(),
                saga.getAmount(), saga.getStatus(), saga.getReason(), Instant.now()));
    }
}
