package com.banks.fraud.e2e;

import com.banks.fraud.account.AccountServiceApplication;
import com.banks.fraud.account.dto.AccountResponse;
import com.banks.fraud.account.dto.CreateAccountRequest;
import com.banks.fraud.common.KafkaTopics;
import com.banks.fraud.common.SagaStatus;
import com.banks.fraud.detection.FraudDetectionServiceApplication;
import com.banks.fraud.notification.NotificationServiceApplication;
import com.banks.fraud.notification.domain.Notification;
import com.banks.fraud.orchestrator.OrchestratorServiceApplication;
import com.banks.fraud.orchestrator.dto.SagaStatusResponse;
import com.banks.fraud.orchestrator.dto.TransferRequest;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the real account, fraud-detection, orchestrator and notification services in one JVM,
 * wired together over a real Kafka broker and a real Redis (Testcontainers), and drives them
 * through their public REST APIs exactly as the gateway would.
 */
@Testcontainers
class TransferSagaEndToEndIT {

    private static final Duration SAGA_TIMEOUT = Duration.ofSeconds(60);

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.7.0");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    private static final List<ConfigurableApplicationContext> services = new ArrayList<>();
    private static RestClient accounts;
    private static RestClient transfers;
    private static RestClient fraud;
    private static RestClient notifications;

    @BeforeAll
    static void startServices() throws Exception {
        createTopics();
        accounts = client(start(AccountServiceApplication.class, "account-service"));
        fraud = client(start(FraudDetectionServiceApplication.class, "fraud-detection-service"));
        transfers = client(start(OrchestratorServiceApplication.class, "orchestrator-service"));
        notifications = client(start(NotificationServiceApplication.class, "notification-service"));
    }

    @AfterAll
    static void stopServices() {
        for (int i = services.size() - 1; i >= 0; i--) {
            services.get(i).close();
        }
    }

    @Test
    void transferCompletesThenBlacklistedTransferIsCompensated() {
        AccountResponse alice = openAccount("Alice", "5000");
        AccountResponse bob = openAccount("Bob", "0");

        // 1. Happy path: debit -> fraud check -> credit
        SagaStatusResponse completed = awaitTerminal(transfer(alice, bob, "100"));

        assertThat(completed.status()).isEqualTo(SagaStatus.COMPLETED);
        assertThat(statuses(completed)).containsExactly(
                SagaStatus.STARTED, SagaStatus.DEBIT_PENDING, SagaStatus.FRAUD_CHECK_PENDING,
                SagaStatus.CREDIT_PENDING, SagaStatus.COMPLETED);
        assertThat(balanceOf(alice)).isEqualByComparingTo("4900");
        assertThat(balanceOf(bob)).isEqualByComparingTo("100");

        // 2. Blacklist the receiver: the debit happens, fraud rejects, the refund compensates it
        fraud.post().uri("/api/fraud/blacklist/{id}", bob.id()).retrieve().toBodilessEntity();

        SagaStatusResponse compensated = awaitTerminal(transfer(alice, bob, "50"));

        assertThat(compensated.status()).isEqualTo(SagaStatus.COMPENSATED);
        assertThat(statuses(compensated)).containsExactly(
                SagaStatus.STARTED, SagaStatus.DEBIT_PENDING, SagaStatus.FRAUD_CHECK_PENDING,
                SagaStatus.FRAUD_REJECTED, SagaStatus.COMPENSATING, SagaStatus.COMPENSATED);
        assertThat(compensated.reason()).contains("BLACKLISTED_ACCOUNT");
        assertThat(balanceOf(alice)).isEqualByComparingTo("4900");
        assertThat(balanceOf(bob)).isEqualByComparingTo("100");

        // 3. Outcomes reach the notification service
        await().atMost(SAGA_TIMEOUT).untilAsserted(() -> {
            assertThat(notificationsOf(alice)).extracting(Notification::getStatus)
                    .containsExactlyInAnyOrder(SagaStatus.COMPLETED, SagaStatus.COMPENSATED);
            assertThat(notificationsOf(bob)).extracting(Notification::getStatus)
                    .containsExactly(SagaStatus.COMPLETED);
        });
    }

    @Test
    void transferWithInsufficientFundsFailsWithoutMovingMoney() {
        AccountResponse carol = openAccount("Carol", "20");
        AccountResponse dave = openAccount("Dave", "0");

        SagaStatusResponse failed = awaitTerminal(transfer(carol, dave, "75"));

        assertThat(failed.status()).isEqualTo(SagaStatus.FAILED);
        assertThat(failed.reason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(balanceOf(carol)).isEqualByComparingTo("20");
        assertThat(balanceOf(dave)).isEqualByComparingTo("0");
    }

    private static ConfigurableApplicationContext start(Class<?> application, String name) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(application).run(
                "--spring.config.name=e2e",
                "--spring.application.name=" + name,
                "--spring.data.redis.host=" + REDIS.getHost(),
                "--spring.data.redis.port=" + REDIS.getMappedPort(6379),
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers());
        services.add(context);
        return context;
    }

    private static RestClient client(ConfigurableApplicationContext context) {
        String port = context.getEnvironment().getProperty("local.server.port");
        return RestClient.create("http://localhost:" + port);
    }

    /** Pre-creates the saga topics so no consumer subscribes to a topic that does not exist yet. */
    private static void createTopics() throws Exception {
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            List<NewTopic> topics = Stream.of(
                            KafkaTopics.DEBIT_COMMAND, KafkaTopics.DEBIT_REPLY,
                            KafkaTopics.CREDIT_COMMAND, KafkaTopics.CREDIT_REPLY,
                            KafkaTopics.REFUND_COMMAND, KafkaTopics.REFUND_REPLY,
                            KafkaTopics.FRAUD_CHECK_COMMAND, KafkaTopics.FRAUD_CHECK_REPLY,
                            KafkaTopics.TRANSACTION_OUTCOME)
                    .map(topic -> new NewTopic(topic, 1, (short) 1))
                    .toList();
            admin.createTopics(topics).all().get();
        }
    }

    private AccountResponse openAccount(String owner, String openingBalance) {
        return accounts.post().uri("/api/accounts")
                .body(new CreateAccountRequest(owner, "EUR", new BigDecimal(openingBalance)))
                .retrieve()
                .body(AccountResponse.class);
    }

    private BigDecimal balanceOf(AccountResponse account) {
        return accounts.get().uri("/api/accounts/{id}", account.id()).retrieve().body(AccountResponse.class).balance();
    }

    private String transfer(AccountResponse from, AccountResponse to, String amount) {
        SagaStatusResponse started = transfers.post().uri("/api/transfers")
                .body(new TransferRequest(from.id(), to.id(), new BigDecimal(amount)))
                .retrieve()
                .body(SagaStatusResponse.class);
        assertThat(started.status()).isEqualTo(SagaStatus.DEBIT_PENDING);
        return started.sagaId();
    }

    private SagaStatusResponse awaitTerminal(String sagaId) {
        return await().atMost(SAGA_TIMEOUT).pollInterval(Duration.ofMillis(200))
                .until(() -> transfers.get().uri("/api/transfers/{id}", sagaId).retrieve().body(SagaStatusResponse.class),
                        saga -> saga.status() == SagaStatus.COMPLETED
                                || saga.status() == SagaStatus.COMPENSATED
                                || saga.status() == SagaStatus.FAILED);
    }

    private List<Notification> notificationsOf(AccountResponse account) {
        return notifications.get().uri("/api/notifications/{id}", account.id())
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });
    }

    private static List<SagaStatus> statuses(SagaStatusResponse saga) {
        return saga.history().stream()
                .map(line -> line.substring(line.indexOf("-> ") + 3).split(" ", 2)[0])
                .map(SagaStatus::valueOf)
                .toList();
    }
}
