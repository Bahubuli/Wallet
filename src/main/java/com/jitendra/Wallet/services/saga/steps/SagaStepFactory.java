package com.jitendra.Wallet.services.saga.steps;

import java.util.Map;

import org.springframework.stereotype.Component;

import com.jitendra.Wallet.services.saga.SagaStep;

@Component
public class SagaStepFactory {
    
    public enum SagaStepType {
        DEBIT_SOURCE_WALLET,
        CREDIT_DESTINATION_WALLET,
        UPDATE_TRANSACTION_STATUS
    }

    private final Map<SagaStepType, SagaStep> stepMap;
    
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
    
    public SagaStep getSagaStep(SagaStepType stepType) {
        SagaStep step = stepMap.get(stepType);
        if (step == null) {
            throw new IllegalArgumentException("Invalid Saga Step Type: " + stepType);
        }
        return step;
    }
}
