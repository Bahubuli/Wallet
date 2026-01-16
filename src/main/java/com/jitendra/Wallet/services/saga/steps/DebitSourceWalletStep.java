package com.jitendra.Wallet.services.saga.steps;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;

import com.jitendra.Wallet.entity.Wallet;
import com.jitendra.Wallet.repository.WalletRepository;
import com.jitendra.Wallet.services.saga.SagaContext;
import com.jitendra.Wallet.services.saga.SagaStep;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class DebitSourceWalletStep implements SagaStep {

    private final WalletRepository walletRepository;

    @Override
    @Transactional
    public boolean execute(SagaContext context) throws Exception {
        log.info("Executing DebitSourceWalletStep for sagaInstanceId: {}", context.getSagaInstanceId());

        BigDecimal amount = (BigDecimal) context.getData().get("amount");
        Long sourceWalletId = (Long) context.getData().get("sourceWalletId");

        Wallet wallet = walletRepository.findByIdWithLock(sourceWalletId)
            .orElseThrow(() -> new RuntimeException("Source Wallet not Found"));

        context.put("fromWalletBalanceBeforeDebit", wallet.getBalance());

        if(!wallet.hasSufficientBalance(amount)) {
            log.error("Insufficient balance in source wallet id: {}. Available balance: {}, Required amount: {}", sourceWalletId, wallet.getBalance(), amount);
            throw new RuntimeException("Insufficient balance in source wallet id: " + sourceWalletId);
        }

        wallet.debit(amount);
        walletRepository.save(wallet);

        context.put("fromWalletBalanceAfterDebit", wallet.getBalance());
        log.info("Debited amount: {} from source wallet id: {}. New balance: {}", amount, sourceWalletId, wallet.getBalance());
        return true;
    }

    @Override
    public boolean compensate(SagaContext context) throws Exception {
        log.info("Compensating DebitSourceWalletStep for sagaInstanceId: {}", context.getSagaInstanceId());

        BigDecimal amount = (BigDecimal) context.getData().get("amount");
        Long sourceWalletId = (Long) context.getData().get("sourceWalletId");

        Wallet wallet = walletRepository.findByIdWithLock(sourceWalletId)
            .orElseThrow(() -> new RuntimeException("Source Wallet not Found"));

        context.put("fromWalletBalanceBeforeDebit", wallet.getBalance());

        wallet.credit(amount);
        walletRepository.save(wallet);

        context.put("fromWalletBalanceAfterDebit", wallet.getBalance());
        log.info("Credited amount: {} back to source wallet id: {}. New balance: {}", amount, sourceWalletId, wallet.getBalance());
        return true;
    }

    @Override
    public String getStepName() {
        return "DEBIT_SOURCE_WALLET";
    }

    @Override
    public Integer getStepOrder() {
        return 1;
    }
}