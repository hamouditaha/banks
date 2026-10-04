package com.banks.fraud.orchestrator.service;

import com.banks.fraud.common.KafkaTopics;
import com.banks.fraud.common.SagaStatus;
import com.banks.fraud.common.event.DebitCommand;
import com.banks.fraud.orchestrator.domain.SagaState;
import com.banks.fraud.orchestrator.dto.SagaStatusResponse;
import com.banks.fraud.orchestrator.dto.TransferRequest;
import com.banks.fraud.orchestrator.exception.SagaNotFoundException;
import com.banks.fraud.orchestrator.repository.SagaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {

    private final SagaRepository sagaRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    /** Kicks off a new transfer saga and returns immediately - the flow completes asynchronously over Kafka. */
    public SagaStatusResponse initiateTransfer(TransferRequest request) {
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new IllegalArgumentException("fromAccountId and toAccountId must differ");
        }

        String sagaId = UUID.randomUUID().toString();
        String transactionId = UUID.randomUUID().toString();
        Instant now = Instant.now();

        SagaState saga = SagaState.builder()
                .sagaId(sagaId)
                .transactionId(transactionId)
                .fromAccountId(request.fromAccountId())
                .toAccountId(request.toAccountId())
                .amount(request.amount())
                .status(SagaStatus.STARTED)
                .history(new java.util.ArrayList<>(List.of(now + " -> STARTED")))
                .createdAt(now)
                .updatedAt(now)
                .build();

        saga.moveTo(SagaStatus.DEBIT_PENDING, "requesting debit from " + request.fromAccountId());
        sagaRepository.save(saga);

        kafkaTemplate.send(KafkaTopics.DEBIT_COMMAND, sagaId,
                new DebitCommand(sagaId, transactionId, request.fromAccountId(), request.amount()));

        log.info("Started transfer saga {}: {} -> {} amount {}", sagaId, request.fromAccountId(), request.toAccountId(), request.amount());
        return SagaStatusResponse.from(saga);
    }

    public SagaStatusResponse getStatus(String sagaId) {
        SagaState saga = sagaRepository.findById(sagaId).orElseThrow(() -> new SagaNotFoundException(sagaId));
        return SagaStatusResponse.from(saga);
    }

    /** Lists transfers newest-first, optionally only those where {@code accountId} is the sender or receiver. */
    public List<SagaStatusResponse> list(String accountId) {
        return sagaRepository.findAll().stream()
                .filter(saga -> accountId == null
                        || accountId.equals(saga.getFromAccountId())
                        || accountId.equals(saga.getToAccountId()))
                .sorted(Comparator.comparing(SagaState::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(SagaStatusResponse::from)
                .toList();
    }
}
