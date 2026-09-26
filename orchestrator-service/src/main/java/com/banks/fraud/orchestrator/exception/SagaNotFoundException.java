package com.banks.fraud.orchestrator.exception;

public class SagaNotFoundException extends RuntimeException {
    public SagaNotFoundException(String sagaId) {
        super("Saga not found: " + sagaId);
    }
}
