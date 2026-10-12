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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Unit tests of the saga state machine: every reply handler is exercised against an
 * in-memory saga store, asserting both the resulting status and the next Kafka command.
 */
@ExtendWith(MockitoExtension.class)
class SagaOrchestratorTest {

    private static final String SAGA_ID = "saga-1";
    private static final String FROM = "acc-from";
    private static final String TO = "acc-to";
    private static final BigDecimal AMOUNT = new BigDecimal("100.00");

    @Mock
    private SagaRepository sagaRepository;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private final Map<String, SagaState> store = new HashMap<>();
    private SagaOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new SagaOrchestrator(sagaRepository, kafkaTemplate);
        lenient().when(sagaRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.<String>getArgument(0))));
        lenient().doAnswer(inv -> {
            SagaState saga = inv.getArgument(0);
            store.put(saga.getSagaId(), saga);
            return null;
        }).when(sagaRepository).save(any());
    }

    @Nested
    class HappyPath {

        @Test
        void successfulDebitMovesToFraudCheckAndRequestsIt() {
            givenSagaIn(SagaStatus.DEBIT_PENDING);

            orchestrator.onDebitReply(debitReply(true, null));

            assertThat(saga().getStatus()).isEqualTo(SagaStatus.FRAUD_CHECK_PENDING);
            FraudCheckCommand command = sent(KafkaTopics.FRAUD_CHECK_COMMAND, FraudCheckCommand.class);
            assertThat(command.sagaId()).isEqualTo(SAGA_ID);
            assertThat(command.fromAccountId()).isEqualTo(FROM);
            assertThat(command.toAccountId()).isEqualTo(TO);
            assertThat(command.amount()).isEqualByComparingTo(AMOUNT);
        }

        @Test
        void approvedFraudCheckMovesToCreditAndCreditsTheReceiver() {
            givenSagaIn(SagaStatus.FRAUD_CHECK_PENDING);

            orchestrator.onFraudCheckReply(fraudReply(true, 10));

            assertThat(saga().getStatus()).isEqualTo(SagaStatus.CREDIT_PENDING);
            CreditCommand command = sent(KafkaTopics.CREDIT_COMMAND, CreditCommand.class);
            assertThat(command.accountId()).isEqualTo(TO);
            assertThat(command.amount()).isEqualByComparingTo(AMOUNT);
        }

        @Test
        void successfulCreditCompletesTheSagaAndPublishesTheOutcome() {
            givenSagaIn(SagaStatus.CREDIT_PENDING);

            orchestrator.onCreditReply(creditReply(true, null));

            assertThat(saga().getStatus()).isEqualTo(SagaStatus.COMPLETED);
            TransactionOutcomeEvent outcome = sent(KafkaTopics.TRANSACTION_OUTCOME, TransactionOutcomeEvent.class);
            assertThat(outcome.status()).isEqualTo(SagaStatus.COMPLETED);
            assertThat(outcome.reason()).isNull();
        }

        @Test
        void fullSequenceRecordsEveryTransitionInOrder() {
            givenSagaIn(SagaStatus.DEBIT_PENDING);

            orchestrator.onDebitReply(debitReply(true, null));
            orchestrator.onFraudCheckReply(fraudReply(true, 0));
            orchestrator.onCreditReply(creditReply(true, null));

            assertThat(statusesInHistory()).containsExactly(
                    SagaStatus.DEBIT_PENDING, SagaStatus.FRAUD_CHECK_PENDING,
                    SagaStatus.CREDIT_PENDING, SagaStatus.COMPLETED);
        }
    }

    @Nested
    class DebitFailure {

        @Test
        void failedDebitEndsTheSagaAsFailedWithoutCompensation() {
            givenSagaIn(SagaStatus.DEBIT_PENDING);

            orchestrator.onDebitReply(debitReply(false, "INSUFFICIENT_FUNDS"));

            assertThat(saga().getStatus()).isEqualTo(SagaStatus.FAILED);
            assertThat(saga().getReason()).isEqualTo("INSUFFICIENT_FUNDS");
            TransactionOutcomeEvent outcome = sent(KafkaTopics.TRANSACTION_OUTCOME, TransactionOutcomeEvent.class);
            assertThat(outcome.status()).isEqualTo(SagaStatus.FAILED);
            verify(kafkaTemplate, never()).send(eq(KafkaTopics.REFUND_COMMAND), anyString(), any());
            verify(kafkaTemplate, never()).send(eq(KafkaTopics.FRAUD_CHECK_COMMAND), anyString(), any());
        }
    }

    @Nested
    class FraudRejection {

        @Test
        void rejectionStartsCompensationByRefundingTheSender() {
            givenSagaIn(SagaStatus.FRAUD_CHECK_PENDING);

            orchestrator.onFraudCheckReply(fraudReply(false, 100));

            assertThat(saga().getStatus()).isEqualTo(SagaStatus.COMPENSATING);
            assertThat(statusesInHistory()).endsWith(SagaStatus.FRAUD_REJECTED, SagaStatus.COMPENSATING);
            assertThat(saga().getReason()).contains("score=100").contains("BLACKLISTED_ACCOUNT");
            RefundCommand refund = sent(KafkaTopics.REFUND_COMMAND, RefundCommand.class);
            assertThat(refund.accountId()).isEqualTo(FROM);
            assertThat(refund.amount()).isEqualByComparingTo(AMOUNT);
            verify(kafkaTemplate, never()).send(eq(KafkaTopics.CREDIT_COMMAND), anyString(), any());
        }

        @Test
        void successfulRefundEndsTheSagaAsCompensated() {
            givenSagaIn(SagaStatus.FRAUD_CHECK_PENDING);
            orchestrator.onFraudCheckReply(fraudReply(false, 100));

            orchestrator.onRefundReply(refundReply(true, null));

            assertThat(saga().getStatus()).isEqualTo(SagaStatus.COMPENSATED);
            assertThat(statusesInHistory()).containsExactly(
                    SagaStatus.FRAUD_CHECK_PENDING, SagaStatus.FRAUD_REJECTED,
                    SagaStatus.COMPENSATING, SagaStatus.COMPENSATED);
            TransactionOutcomeEvent outcome = sent(KafkaTopics.TRANSACTION_OUTCOME, TransactionOutcomeEvent.class);
            assertThat(outcome.status()).isEqualTo(SagaStatus.COMPENSATED);
            assertThat(outcome.reason()).contains("fraud rejected");
        }
    }

    @Nested
    class CreditFailure {

        @Test
        void failedCreditTriggersCompensation() {
            givenSagaIn(SagaStatus.CREDIT_PENDING);

            orchestrator.onCreditReply(creditReply(false, "ACCOUNT_NOT_FOUND"));

            assertThat(saga().getStatus()).isEqualTo(SagaStatus.COMPENSATING);
            assertThat(statusesInHistory()).endsWith(SagaStatus.CREDIT_FAILED, SagaStatus.COMPENSATING);
            assertThat(saga().getReason()).isEqualTo("credit failed: ACCOUNT_NOT_FOUND");
            RefundCommand refund = sent(KafkaTopics.REFUND_COMMAND, RefundCommand.class);
            assertThat(refund.accountId()).isEqualTo(FROM);
            verify(kafkaTemplate, never()).send(eq(KafkaTopics.TRANSACTION_OUTCOME), anyString(), any());
        }

        @Test
        void successfulRefundAfterCreditFailureEndsAsCompensated() {
            givenSagaIn(SagaStatus.CREDIT_PENDING);
            orchestrator.onCreditReply(creditReply(false, "ACCOUNT_NOT_FOUND"));

            orchestrator.onRefundReply(refundReply(true, null));

            assertThat(saga().getStatus()).isEqualTo(SagaStatus.COMPENSATED);
            assertThat(sent(KafkaTopics.TRANSACTION_OUTCOME, TransactionOutcomeEvent.class).status())
                    .isEqualTo(SagaStatus.COMPENSATED);
        }

        @Test
        void failedRefundEndsAsFailedAndKeepsBothReasons() {
            givenSagaIn(SagaStatus.CREDIT_PENDING);
            orchestrator.onCreditReply(creditReply(false, "ACCOUNT_NOT_FOUND"));

            orchestrator.onRefundReply(refundReply(false, "LOCK_TIMEOUT"));

            assertThat(saga().getStatus()).isEqualTo(SagaStatus.FAILED);
            assertThat(saga().getReason())
                    .isEqualTo("credit failed: ACCOUNT_NOT_FOUND | REFUND FAILED: LOCK_TIMEOUT");
            assertThat(sent(KafkaTopics.TRANSACTION_OUTCOME, TransactionOutcomeEvent.class).status())
                    .isEqualTo(SagaStatus.FAILED);
        }
    }

    @Nested
    class DuplicateAndOutOfOrderDelivery {

        @ParameterizedTest
        @EnumSource(value = SagaStatus.class, names = "DEBIT_PENDING", mode = EnumSource.Mode.EXCLUDE)
        void debitReplyIsIgnoredOutsideDebitPending(SagaStatus current) {
            givenSagaIn(current);

            orchestrator.onDebitReply(debitReply(true, null));

            assertUntouched(current);
        }

        @ParameterizedTest
        @EnumSource(value = SagaStatus.class, names = "FRAUD_CHECK_PENDING", mode = EnumSource.Mode.EXCLUDE)
        void fraudReplyIsIgnoredOutsideFraudCheckPending(SagaStatus current) {
            givenSagaIn(current);

            orchestrator.onFraudCheckReply(fraudReply(false, 100));

            assertUntouched(current);
        }

        @ParameterizedTest
        @EnumSource(value = SagaStatus.class, names = "CREDIT_PENDING", mode = EnumSource.Mode.EXCLUDE)
        void creditReplyIsIgnoredOutsideCreditPending(SagaStatus current) {
            givenSagaIn(current);

            orchestrator.onCreditReply(creditReply(true, null));

            assertUntouched(current);
        }

        @ParameterizedTest
        @EnumSource(value = SagaStatus.class, names = "COMPENSATING", mode = EnumSource.Mode.EXCLUDE)
        void refundReplyIsIgnoredOutsideCompensating(SagaStatus current) {
            givenSagaIn(current);

            orchestrator.onRefundReply(refundReply(true, null));

            assertUntouched(current);
        }

        @Test
        void redeliveredCreditReplyDoesNotPublishTheOutcomeTwice() {
            givenSagaIn(SagaStatus.CREDIT_PENDING);

            orchestrator.onCreditReply(creditReply(true, null));
            orchestrator.onCreditReply(creditReply(true, null));

            verify(kafkaTemplate).send(eq(KafkaTopics.TRANSACTION_OUTCOME), eq(SAGA_ID), any());
        }

        @Test
        void replyForUnknownSagaIsIgnored() {
            orchestrator.onDebitReply(debitReply(true, null));

            verify(sagaRepository, never()).save(any());
            verifyNoInteractions(kafkaTemplate);
        }

        private void assertUntouched(SagaStatus expected) {
            assertThat(saga().getStatus()).isEqualTo(expected);
            verify(sagaRepository, never()).save(any());
            verifyNoInteractions(kafkaTemplate);
        }
    }

    private void givenSagaIn(SagaStatus status) {
        Instant now = Instant.now();
        SagaState saga = SagaState.builder()
                .sagaId(SAGA_ID)
                .transactionId("tx-1")
                .fromAccountId(FROM)
                .toAccountId(TO)
                .amount(AMOUNT)
                .status(status)
                .history(new ArrayList<>(List.of(now + " -> " + status)))
                .createdAt(now)
                .updatedAt(now)
                .build();
        store.put(SAGA_ID, saga);
    }

    private SagaState saga() {
        return store.get(SAGA_ID);
    }

    private List<SagaStatus> statusesInHistory() {
        return saga().getHistory().stream()
                .map(line -> line.substring(line.indexOf("-> ") + 3).split(" ", 2)[0])
                .map(SagaStatus::valueOf)
                .toList();
    }

    private <T> T sent(String topic, Class<T> type) {
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(topic), eq(SAGA_ID), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(type);
        return type.cast(payload.getValue());
    }

    private DebitReply debitReply(boolean success, String reason) {
        return new DebitReply(SAGA_ID, "tx-1", FROM, AMOUNT, success, reason);
    }

    private FraudCheckReply fraudReply(boolean approved, int score) {
        return new FraudCheckReply(SAGA_ID, "tx-1", approved, score,
                approved ? List.of() : List.of("BLACKLISTED_ACCOUNT"));
    }

    private CreditReply creditReply(boolean success, String reason) {
        return new CreditReply(SAGA_ID, "tx-1", TO, AMOUNT, success, reason);
    }

    private RefundReply refundReply(boolean success, String reason) {
        return new RefundReply(SAGA_ID, "tx-1", FROM, AMOUNT, success, reason);
    }
}
