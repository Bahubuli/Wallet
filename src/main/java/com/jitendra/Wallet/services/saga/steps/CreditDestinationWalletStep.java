package com.jitendra.Wallet.services.saga.steps;

import org.springframework.stereotype.Service;

import com.jitendra.Wallet.repository.WalletRepository;
import com.jitendra.Wallet.services.saga.SagaContext;
import com.jitendra.Wallet.services.saga.SagaStep;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CreditDestinationWalletStep implements SagaStep {


    private final WalletRepository walletRepository;

    @Override
    public void execute(SagaContext context) throws Exception {
        // Implementation for crediting the destination wallet
        log.info("Executing CreditDestinationWalletStep for sagaInstanceId: {}", context.getSagaInstanceId());
      
        // Simulate credit operation

        // step 1 : get the destination wallet id and amount from context 

        // step 2 : fetch the destination wallet from the database with a lock 

        // step 3 : credit the destination wallet with the amount

        // step 4 : update context and log the success message

    }

    @Override
    public void compensate(SagaContext context) throws Exception {
        // Implementation for compensating the credit operation
        log.info("Compensating CreditDestinationWalletStep for sagaInstanceId: {}", context.getSagaInstanceId());
       
    }

    @Override
    public String getStepName() {
        return "CREDIT_DESTINATION_WALLET";
    }

    @Override
    public Integer getStepOrder() {
        return 2;
    }
    
}
