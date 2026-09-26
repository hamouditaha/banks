package com.banks.fraud.orchestrator.web;

import com.banks.fraud.orchestrator.dto.SagaStatusResponse;
import com.banks.fraud.orchestrator.dto.TransferRequest;
import com.banks.fraud.orchestrator.service.TransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;

    @PostMapping
    public ResponseEntity<SagaStatusResponse> transfer(@Valid @RequestBody TransferRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(transferService.initiateTransfer(request));
    }

    @GetMapping("/{sagaId}")
    public SagaStatusResponse getStatus(@PathVariable String sagaId) {
        return transferService.getStatus(sagaId);
    }

    @GetMapping
    public List<SagaStatusResponse> listAll() {
        return transferService.listAll();
    }
}
