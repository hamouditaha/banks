package com.banks.fraud.account.kafka;

import com.banks.fraud.account.dto.AccountResponse;
import com.banks.fraud.account.dto.CreateAccountRequest;
import com.banks.fraud.account.service.AccountService;
import com.banks.fraud.common.KafkaTopics;
import com.banks.fraud.common.event.CreditCommand;
import com.banks.fraud.common.event.CreditReply;
import com.banks.fraud.common.event.DebitCommand;
import com.banks.fraud.common.event.DebitReply;
import com.banks.fraud.common.event.RefundCommand;
import com.banks.fraud.common.event.RefundReply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Replays the same saga command ({@code sagaId:step}) against a real Redis and checks that the
 * balance moves exactly once while every delivery still gets the same reply.
 * The Kafka listener methods are invoked directly, exactly as the listener container would.
 */
@Testcontainers
@SpringBootTest(properties = {
        "eureka.client.enabled=false",
        "spring.kafka.listener.auto-startup=false"
})
class AccountCommandIdempotencyIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private AccountCommandListener listener;
    @Autowired
    private AccountService accountService;
    @MockBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    private String accountId;
    private String sagaId;

    @BeforeEach
    void setUp() {
        accountId = accountService.createAccount(new CreateAccountRequest("Alice", "EUR", new BigDecimal("1000"))).id();
        sagaId = UUID.randomUUID().toString();
        clearInvocations(kafkaTemplate);
    }

    @Test
    void redeliveredDebitCommandDebitsOnlyOnce() {
        DebitCommand command = new DebitCommand(sagaId, "tx", accountId, new BigDecimal("150.25"));

        listener.onDebitCommand(command);
        listener.onDebitCommand(command);

        assertThat(balance()).isEqualByComparingTo("849.75");
        assertThat(replies(KafkaTopics.DEBIT_REPLY, DebitReply.class, 2))
                .allSatisfy(reply -> assertThat(reply.success()).isTrue());
    }

    @Test
    void redeliveredCreditCommandCreditsOnlyOnce() {
        CreditCommand command = new CreditCommand(sagaId, "tx", accountId, new BigDecimal("200"));

        listener.onCreditCommand(command);
        listener.onCreditCommand(command);

        assertThat(balance()).isEqualByComparingTo("1200");
        assertThat(replies(KafkaTopics.CREDIT_REPLY, CreditReply.class, 2))
                .allSatisfy(reply -> assertThat(reply.success()).isTrue());
    }

    @Test
    void redeliveredRefundCommandRefundsOnlyOnce() {
        listener.onDebitCommand(new DebitCommand(sagaId, "tx", accountId, new BigDecimal("300")));
        RefundCommand refund = new RefundCommand(sagaId, "tx", accountId, new BigDecimal("300"), "fraud rejected");

        listener.onRefundCommand(refund);
        listener.onRefundCommand(refund);

        assertThat(balance()).isEqualByComparingTo("1000");
        assertThat(replies(KafkaTopics.REFUND_REPLY, RefundReply.class, 2))
                .allSatisfy(reply -> assertThat(reply.success()).isTrue());
    }

    @Test
    void stepsOfTheSameSagaAreKeyedSeparately() {
        // debit and refund share the sagaId but not the step, so the refund must not be deduplicated away
        listener.onDebitCommand(new DebitCommand(sagaId, "tx", accountId, new BigDecimal("100")));
        listener.onRefundCommand(new RefundCommand(sagaId, "tx", accountId, new BigDecimal("100"), "test"));

        assertThat(balance()).isEqualByComparingTo("1000");
    }

    @Test
    void replayedFailureStaysAFailureEvenIfFundsArriveLater() {
        DebitCommand command = new DebitCommand(sagaId, "tx", accountId, new BigDecimal("5000"));

        listener.onDebitCommand(command);
        accountService.deposit(accountId, new BigDecimal("10000"));
        listener.onDebitCommand(command);

        assertThat(balance()).isEqualByComparingTo("11000");
        assertThat(replies(KafkaTopics.DEBIT_REPLY, DebitReply.class, 2))
                .allSatisfy(reply -> {
                    assertThat(reply.success()).isFalse();
                    assertThat(reply.reason()).isEqualTo("INSUFFICIENT_FUNDS");
                });
    }

    @Test
    void concurrentDuplicateDeliveriesDebitOnlyOnce() throws Exception {
        DebitCommand command = new DebitCommand(sagaId, "tx", accountId, new BigDecimal("10"));
        List<Callable<Void>> deliveries = IntStream.range(0, 8)
                .<Callable<Void>>mapToObj(i -> () -> {
                    listener.onDebitCommand(command);
                    return null;
                })
                .toList();

        ExecutorService pool = Executors.newFixedThreadPool(deliveries.size());
        try {
            for (var future : pool.invokeAll(deliveries)) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(balance()).isEqualByComparingTo("990");
        assertThat(replies(KafkaTopics.DEBIT_REPLY, DebitReply.class, deliveries.size()))
                .allSatisfy(reply -> assertThat(reply.success()).isTrue());
    }

    private BigDecimal balance() {
        AccountResponse account = accountService.getAccount(accountId);
        return account.balance();
    }

    private <T> List<T> replies(String topic, Class<T> type, int expected) {
        ArgumentCaptor<Object> payloads = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, times(expected)).send(eq(topic), eq(sagaId), payloads.capture());
        return payloads.getAllValues().stream().map(type::cast).toList();
    }
}
