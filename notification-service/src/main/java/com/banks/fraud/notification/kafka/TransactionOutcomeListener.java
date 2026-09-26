package com.banks.fraud.notification.kafka;

import com.banks.fraud.common.KafkaTopics;
import com.banks.fraud.common.event.TransactionOutcomeEvent;
import com.banks.fraud.notification.domain.Notification;
import com.banks.fraud.notification.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class TransactionOutcomeListener {

    private final NotificationRepository notificationRepository;

    @KafkaListener(topics = KafkaTopics.TRANSACTION_OUTCOME, groupId = "notification-service")
    public void onTransactionOutcome(TransactionOutcomeEvent event) {
        log.info("Notifying parties of saga {} outcome: {}", event.sagaId(), event.status());

        notificationRepository.add(build(event, event.fromAccountId(), messageFor(event, true)));
        if (event.status().name().equals("COMPLETED")) {
            notificationRepository.add(build(event, event.toAccountId(), messageFor(event, false)));
        }
    }

    private Notification build(TransactionOutcomeEvent event, String accountId, String message) {
        return Notification.builder()
                .id(UUID.randomUUID().toString())
                .accountId(accountId)
                .sagaId(event.sagaId())
                .amount(event.amount())
                .status(event.status())
                .message(message)
                .occurredAt(event.occurredAt())
                .build();
    }

    private String messageFor(TransactionOutcomeEvent event, boolean forSender) {
        return switch (event.status()) {
            case COMPLETED -> forSender
                    ? "Transfer of %s sent successfully to account %s.".formatted(event.amount(), event.toAccountId())
                    : "You received %s from account %s.".formatted(event.amount(), event.fromAccountId());
            case COMPENSATED -> "Transfer of %s was reversed: %s".formatted(event.amount(), event.reason());
            case FAILED -> "Transfer of %s failed: %s".formatted(event.amount(), event.reason());
            default -> "Transfer of %s is now %s.".formatted(event.amount(), event.status());
        };
    }
}
