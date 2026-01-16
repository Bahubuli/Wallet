package com.jitendra.Wallet.services.saga.steps;

import org.springframework.stereotype.Service;

import com.jitendra.Wallet.entity.Transaction;
import com.jitendra.Wallet.entity.TransactionStatus;
import com.jitendra.Wallet.repository.TransactionRepository;
import com.jitendra.Wallet.services.saga.SagaContext;
import com.jitendra.Wallet.services.saga.SagaStep;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class UpdateTransactionStatus implements SagaStep {

    private final TransactionRepository transactionRepository;

    @Override
    @Transactional
    public boolean execute(SagaContext context) throws Exception {
        log.info("Executing UpdateTransactionStatus for sagaInstanceId: {}", context.getSagaInstanceId());

        Long transactionId = (Long) context.getData().get("transactionId");
        String newStatus = (String) context.getData().get("newStatus");

        Transaction transaction = transactionRepository.findById(transactionId)
            .orElseThrow(() -> new RuntimeException("Transaction not found for transactionId: " + transactionId));

        TransactionStatus statusBeforeUpdate = transaction.getStatus();
        context.put("transactionStatusBefore", statusBeforeUpdate);

        transaction.setStatus(TransactionStatus.valueOf(newStatus));
        transactionRepository.save(transaction);

        context.put("transactionStatusAfter", transaction.getStatus());
        log.info("Updated transaction id: {} status from {} to {}. SagaInstanceId: {}", 
            transactionId, statusBeforeUpdate, newStatus, context.getSagaInstanceId());
        return true;
    }

    @Override
    public boolean compensate(SagaContext context) throws Exception {
        log.info("Compensating UpdateTransactionStatus for sagaInstanceId: {}", context.getSagaInstanceId());

        Long transactionId = (Long) context.getData().get("transactionId");

        Transaction transaction = transactionRepository.findById(transactionId)
            .orElseThrow(() -> new RuntimeException("Transaction not found for transactionId: " + transactionId));

        TransactionStatus previousStatus = (TransactionStatus) context.getData().get("transactionStatusBefore");
        context.put("transactionStatusBefore", transaction.getStatus());

        transaction.setStatus(previousStatus);
        transactionRepository.save(transaction);

        context.put("transactionStatusAfter", transaction.getStatus());
        log.info("Reverted transaction id: {} status back to {}. SagaInstanceId: {}", 
            transactionId, previousStatus, context.getSagaInstanceId());
        return true;
    }

    @Override
    public String getStepName() {
        return "UPDATE_TRANSACTION_STATUS";
    }

    @Override
    public Integer getStepOrder() {
        return 3;
    }
}
