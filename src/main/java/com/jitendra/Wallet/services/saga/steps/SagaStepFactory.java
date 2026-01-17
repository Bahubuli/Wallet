package com.jitendra.Wallet.services.saga.steps;

import org.springframework.stereotype.Component;

@Component
public class SagaStepFactory {
    public static enum SagaStepType {
       DEBIT_SOURCE_WALLET,
       CREDIT_DESTINATION_WALLET,
       UPDATE_TRANSACTION_STATUS
    }

    // here we have a problem that is these classes have dependencies
    // so each of these classes need repository instances
    
    public static SagaStep getSagaStep(SagaStepType stepType) {
        switch (stepType) {
            case DEBIT_SOURCE_WALLET:
                return new DebitSourceWalletStep();
            case CREDIT_DESTINATION_WALLET:
                return new CreditDestinationWalletStep();
            case UPDATE_TRANSACTION_STATUS:
                return new UpdateTransactionStatusStep();
            default:
                throw new IllegalArgumentException("Invalid Saga Step Type");
        }
    }
}
