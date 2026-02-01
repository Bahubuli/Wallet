package com.jitendra.Wallet.services.saga.steps;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.jitendra.Wallet.services.saga.SagaStepInterface;

@Component
public class SagaStepFactory {
    
    public enum SagaStepType {
        DEBIT_SOURCE_WALLET,
        CREDIT_DESTINATION_WALLET,
        UPDATE_TRANSACTION_STATUS
    }

    private final Map<SagaStepType, SagaStepInterface> stepMap;
    
    public SagaStepFactory(
            DebitSourceWalletStep debitSourceWalletStep,
            CreditDestinationWalletStep creditDestinationWalletStep,
            UpdateTransactionStatus updateTransactionStatus) {
        this.stepMap = Map.of(
            SagaStepType.DEBIT_SOURCE_WALLET, debitSourceWalletStep,
            SagaStepType.CREDIT_DESTINATION_WALLET, creditDestinationWalletStep,
            SagaStepType.UPDATE_TRANSACTION_STATUS, updateTransactionStatus
        );
    }
    
    public SagaStepInterface getSagaStep(SagaStepType stepType) {
        SagaStepInterface step = stepMap.get(stepType);
        if (step == null) {
            throw new IllegalArgumentException("Invalid Saga Step Type: " + stepType);
        }
        return step;
    }

    /**
     * Returns the SagaStepInterface for the given step name (case-sensitive, must match enum name).
     * Throws IllegalArgumentException if the step name is invalid.
     */
    public SagaStepInterface getSagaStepByName(String stepName) {
        try {
            SagaStepType stepType = SagaStepType.valueOf(stepName);
            return getSagaStep(stepType);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Saga Step Name: " + stepName, e);
        }
    }
}
