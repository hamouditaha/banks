package com.banks.fraud.account.kafka;

import com.banks.fraud.common.KafkaTopics;
import com.banks.fraud.common.event.CreditCommand;
import com.banks.fraud.common.event.CreditReply;
import com.banks.fraud.common.event.DebitCommand;
import com.banks.fraud.common.event.DebitReply;
import com.banks.fraud.common.event.RefundCommand;
import com.banks.fraud.common.event.RefundReply;
import com.banks.fraud.account.service.AccountService;
import com.banks.fraud.account.service.OperationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Participant side of the SAGA: reacts to debit/credit/refund commands issued by the
 * orchestrator-service and publishes the corresponding reply event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountCommandListener {

    private final AccountService accountService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = KafkaTopics.DEBIT_COMMAND, groupId = "account-service")
    public void onDebitCommand(DebitCommand command) {
        log.info("Received debit command for saga {}", command.sagaId());
        OperationResult result = accountService.debit(command.accountId(), command.amount(), command.sagaId() + ":debit");
        kafkaTemplate.send(KafkaTopics.DEBIT_REPLY, command.sagaId(), new DebitReply(
                command.sagaId(), command.transactionId(), command.accountId(), command.amount(),
                result.success(), result.reason()));
    }

    @KafkaListener(topics = KafkaTopics.CREDIT_COMMAND, groupId = "account-service")
    public void onCreditCommand(CreditCommand command) {
        log.info("Received credit command for saga {}", command.sagaId());
        OperationResult result = accountService.credit(command.accountId(), command.amount(), command.sagaId() + ":credit");
        kafkaTemplate.send(KafkaTopics.CREDIT_REPLY, command.sagaId(), new CreditReply(
                command.sagaId(), command.transactionId(), command.accountId(), command.amount(),
                result.success(), result.reason()));
    }

    @KafkaListener(topics = KafkaTopics.REFUND_COMMAND, groupId = "account-service")
    public void onRefundCommand(RefundCommand command) {
        log.info("Received compensating refund command for saga {}: {}", command.sagaId(), command.reason());
        OperationResult result = accountService.credit(command.accountId(), command.amount(), command.sagaId() + ":refund");
        kafkaTemplate.send(KafkaTopics.REFUND_REPLY, command.sagaId(), new RefundReply(
                command.sagaId(), command.transactionId(), command.accountId(), command.amount(),
                result.success(), result.reason()));
    }
}
