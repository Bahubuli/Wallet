package com.jitendra.Wallet.services.saga.steps;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;

import com.jitendra.Wallet.entity.Wallet;
import com.jitendra.Wallet.repository.WalletRepository;
import com.jitendra.Wallet.services.saga.SagaContext;
import com.jitendra.Wallet.services.saga.SagaStepInterface;
import com.jitendra.Wallet.services.saga.steps.SagaStepFactory.SagaStepType;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class CreditDestinationWalletStep implements SagaStepInterface {

    private final WalletRepository walletRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean execute(SagaContext context) throws Exception {
        // Implementation for crediting the destination wallet
        log.info("Executing CreditDestinationWalletStep for sagaInstanceId: {}", context.getSagaInstanceId());

        BigDecimal amount = new BigDecimal(context.getData().get("amount").toString());
        // Simulate credit operation

        // step 1 : get the destination wallet id and amount from context
        Long destinationWalletId = Long.valueOf(context.getData().get("destinationWalletId").toString());

        // step 2 : fetch the destination wallet from the database with a lock
        // Optional <Wallet> destinationWalletOpt =
        // walletRepository.findByIdWithLock(destinationWalletId);
        Wallet wallet = walletRepository.findByIdWithLock(destinationWalletId)
                .orElseThrow(() -> new RuntimeException("Destination Wallet not Found"));

        context.put("toWalletBalanceBeforeCredit", wallet.getBalance());

        // step 3 : credit the destination wallet with the amount
        wallet.credit(amount);
        walletRepository.save(wallet);

        // step 4 : update context and log the success message
        context.put("toWalletBalanceAfterCredit", wallet.getBalance());
        log.info("Credited amount: {} to destination wallet id: {}. New balance: {}", amount, destinationWalletId,
                wallet.getBalance());
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean compensate(SagaContext context) throws Exception {
        log.info("Compensating CreditDestinationWalletStep for sagaInstanceId: {}", context.getSagaInstanceId());

        BigDecimal amount = new BigDecimal(context.getData().get("amount").toString());
        // Simulate debit operation

        // step 1 : get the destination wallet id and amount from context
        Long destinationWalletId = Long.valueOf(context.getData().get("destinationWalletId").toString());

        // step 2 : fetch the destination wallet from the database with a lock
        // Optional <Wallet> destinationWalletOpt =
        // walletRepository.findByIdWithLock(destinationWalletId);
        Wallet wallet = walletRepository.findByIdWithLock(destinationWalletId)
                .orElseThrow(() -> new RuntimeException("Destination Wallet not Found"));

        context.put("toWalletBalanceBeforeCredit", wallet.getBalance());

        // step 3 : debit the destination wallet with the amount
        wallet.debit(amount);
        walletRepository.save(wallet);

        // step 4 : update context and log the success message
        context.put("toWalletBalanceAfterCredit", wallet.getBalance());
        log.info("Debited amount: {} from destination wallet id: {}. New balance: {}", amount, destinationWalletId,
                wallet.getBalance());
        return true;

    }

    @Override
    public String getStepName() {
        return SagaStepType.CREDIT_DESTINATION_WALLET.toString();
    }

    @Override
    public Integer getStepOrder() {
        return 2;
    }

}
