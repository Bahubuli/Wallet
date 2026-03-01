package com.jitendra.Wallet.services.saga.steps;

import org.springframework.stereotype.Service;

import com.jitendra.Wallet.entity.Transaction;
import com.jitendra.Wallet.entity.TransactionStatus;
import com.jitendra.Wallet.repository.TransactionRepository;
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
public class UpdateTransactionStatus implements SagaStepInterface {

    private final TransactionRepository transactionRepository;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean execute(SagaContext context) throws Exception {
        log.info("Executing UpdateTransactionStatus for sagaInstanceId: {}", context.getSagaInstanceId());

        Long transactionId = Long.valueOf(context.getData().get("transactionId").toString());
        String newStatus = context.getData().get("newStatus").toString();

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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean compensate(SagaContext context) throws Exception {
        log.info("Compensating UpdateTransactionStatus for sagaInstanceId: {}", context.getSagaInstanceId());

        Long transactionId = Long.valueOf(context.getData().get("transactionId").toString());

        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found for transactionId: " + transactionId));

        TransactionStatus previousStatus = TransactionStatus
                .valueOf(context.getData().get("transactionStatusBefore").toString());
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
        return SagaStepType.UPDATE_TRANSACTION_STATUS.toString();
    }

    @Override
    public Integer getStepOrder() {
        return 3;
    }
}
