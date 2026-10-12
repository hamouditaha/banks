package com.banks.fraud.orchestrator.service;

import com.banks.fraud.common.KafkaTopics;
import com.banks.fraud.common.SagaStatus;
import com.banks.fraud.common.event.DebitCommand;
import com.banks.fraud.orchestrator.domain.SagaState;
import com.banks.fraud.orchestrator.dto.SagaStatusResponse;
import com.banks.fraud.orchestrator.dto.TransferRequest;
import com.banks.fraud.orchestrator.exception.SagaNotFoundException;
import com.banks.fraud.orchestrator.repository.SagaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private SagaRepository sagaRepository;
    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    private TransferService transferService;

    @BeforeEach
    void setUp() {
        transferService = new TransferService(sagaRepository, kafkaTemplate);
    }

    @Test
    void startsTheSagaInDebitPendingAndSendsTheDebitCommand() {
        SagaStatusResponse response = transferService.initiateTransfer(
                new TransferRequest("A", "B", new BigDecimal("42.50")));

        assertThat(response.status()).isEqualTo(SagaStatus.DEBIT_PENDING);
        assertThat(response.history()).hasSize(2);
        assertThat(response.history().get(0)).endsWith("-> STARTED");

        ArgumentCaptor<SagaState> saved = ArgumentCaptor.forClass(SagaState.class);
        verify(sagaRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(SagaStatus.DEBIT_PENDING);

        ArgumentCaptor<Object> command = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(KafkaTopics.DEBIT_COMMAND), eq(response.sagaId()), command.capture());
        assertThat(command.getValue()).isEqualTo(new DebitCommand(
                response.sagaId(), response.transactionId(), "A", new BigDecimal("42.50")));
    }

    @Test
    void rejectsATransferToTheSameAccount() {
        assertThatThrownBy(() -> transferService.initiateTransfer(new TransferRequest("A", "A", BigDecimal.TEN)))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(sagaRepository, kafkaTemplate);
    }

    @Test
    void unknownSagaIdIsReportedAsNotFound() {
        when(sagaRepository.findById(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transferService.getStatus("nope")).isInstanceOf(SagaNotFoundException.class);
    }
}
