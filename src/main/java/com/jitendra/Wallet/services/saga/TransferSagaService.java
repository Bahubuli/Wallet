package com.jitendra.Wallet.services.saga;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jitendra.Wallet.dto.TransactionRequestDTO;
import com.jitendra.Wallet.dto.TransactionResponseDTO;
import com.jitendra.Wallet.entity.Transaction;
import com.jitendra.Wallet.entity.TransactionStatus;
import com.jitendra.Wallet.repository.TransactionRepository;

import com.jitendra.Wallet.services.saga.steps.SagaStepFactory;
import com.jitendra.Wallet.services.saga.steps.SagaStepFactory.SagaStepType;
import com.jitendra.Wallet.services.saga.steps.SagaStepFactory.SagaType;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * TransferSagaService orchestrates the complete wallet transfer saga workflow.
 * It handles Transaction creation, saga step execution, and status updates.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TransferSagaService {

    private final SagaOrchestrator sagaOrchestrator;
    private final SagaStepFactory sagaStepFactory;
    private final TransactionRepository transactionRepository;

    /**
     * Initiates a wallet transfer saga.
     * Creates Transaction, validates wallets, starts saga, and executes all steps.
     * 
     * @param transactionRequest The transfer request details
     * @return TransactionResponseDTO with transaction and saga details
     */
    public TransactionResponseDTO initiateTransfer(TransactionRequestDTO transactionRequest) {
        log.info("Initiating transfer saga from wallet {} to wallet {} with amount {}",
                transactionRequest.getSourceWalletId(),
                transactionRequest.getDestinationWalletId(),
                transactionRequest.getAmount());

        // Atomically create both Transaction and Saga in one transaction
        Object[] created = createTransactionAndStartSaga(transactionRequest);
        Transaction savedTransaction = (Transaction) created[0];
        Long sagaInstanceId = (Long) created[1];
        SagaContext sagaContext = (SagaContext) created[2];

        log.info("Saga started with id: {}, linked to transaction id: {}", sagaInstanceId, savedTransaction.getId());

        // Execute the saga steps and get final status
        boolean success = executeTransferSaga(sagaInstanceId, sagaContext);

        // Update transaction status based on saga result
        savedTransaction = updateTransactionStatus(savedTransaction.getId(), success);

        return mapToResponseDTO(savedTransaction);
    }

    /**
     * Atomically creates both the Transaction and the SagaInstance in a single
     * database transaction. This prevents orphaned transactions (transactions
     * with no associated saga) if the app crashes between the two operations.
     */
    @Transactional
    protected Object[] createTransactionAndStartSaga(TransactionRequestDTO transactionRequest) {
        Instant now = Instant.now();

        // Build saga context first
        Map<String, Object> contextData = new HashMap<>();
        contextData.put("sourceWalletId", transactionRequest.getSourceWalletId());
        contextData.put("destinationWalletId", transactionRequest.getDestinationWalletId());
        contextData.put("amount", transactionRequest.getAmount());
        contextData.put("description",
                transactionRequest.getDescription() != null ? transactionRequest.getDescription() : "");
        contextData.put("transactionType", transactionRequest.getType());
        contextData.put("newStatus", TransactionStatus.SUCCESS.name());

        SagaContext sagaContext = SagaContext.builder()
                .sagaType(SagaType.TRANSACTION_TRANSFER.name())
                .data(contextData)
                .build();

        // Start the saga (creates saga instance in DB)
        Long sagaInstanceId = sagaOrchestrator.startSaga(sagaContext);
        sagaContext.setSagaInstanceId(sagaInstanceId);

        // Create Transaction linked to the saga from the start (no dummy ID)
        Transaction transaction = Transaction.builder()
                .description(transactionRequest.getDescription())
                .sourceWalletId(transactionRequest.getSourceWalletId())
                .destinationWalletId(transactionRequest.getDestinationWalletId())
                .amount(transactionRequest.getAmount())
                .type(transactionRequest.getType())
                .status(TransactionStatus.PENDING)
                .sagaInstanceId(sagaInstanceId)
                .createdDate(now)
                .updatedDate(now)
                .build();

        Transaction savedTransaction = transactionRepository.save(transaction);
        sagaContext.getData().put("transactionId", savedTransaction.getId());

        log.info("Transaction created with id: {} linked to saga id: {}",
                savedTransaction.getId(), sagaInstanceId);

        return new Object[] { savedTransaction, sagaInstanceId, sagaContext };
    }

    /**
     * Executes all steps of the transfer saga in order.
     * If any step fails, triggers compensation to rollback completed steps.
     * 
     * @param sagaInstanceId The saga instance ID
     * @param sagaContext    The saga context with transfer data
     * @return true if all steps succeeded, false otherwise
     */
    private boolean executeTransferSaga(Long sagaInstanceId, SagaContext sagaContext) {
        log.info("Executing transfer saga steps for sagaInstanceId: {}", sagaInstanceId);

        // Get ordered list of steps for TRANSACTION_TRANSFER saga
        List<SagaStepType> steps = sagaStepFactory.getStepsForSaga(SagaType.TRANSACTION_TRANSFER);

        boolean allStepsSucceeded = true;
        String failedStepName = null;
        int stepOrder = 1;

        for (SagaStepType stepType : steps) {
            String stepName = stepType.name();
            log.info("Executing saga step: {} for sagaInstanceId: {}", stepName, sagaInstanceId);

            try {
                boolean stepResult = sagaOrchestrator.executeStep(sagaInstanceId, stepName, stepOrder++);

                if (!stepResult) {
                    log.error("Saga step {} failed for sagaInstanceId: {}", stepName, sagaInstanceId);
                    allStepsSucceeded = false;
                    failedStepName = stepName;
                    break;
                }

                log.info("Saga step {} completed successfully for sagaInstanceId: {}", stepName, sagaInstanceId);

            } catch (Exception e) {
                log.error("Exception during saga step {} for sagaInstanceId: {}: {}",
                        stepName, sagaInstanceId, e.getMessage());
                allStepsSucceeded = false;
                failedStepName = stepName;
                break;
            }
        }

        // Handle saga completion or compensation
        if (allStepsSucceeded) {
            sagaOrchestrator.completeSaga(sagaInstanceId);
            log.info("Transfer saga completed successfully for sagaInstanceId: {}", sagaInstanceId);
        } else {
            log.error("Transfer saga failed at step {} for sagaInstanceId: {}, initiating compensation",
                    failedStepName, sagaInstanceId);
            sagaOrchestrator.compensateSaga(sagaInstanceId);
        }

        return allStepsSucceeded;
    }

    /**
     * Update transaction status based on saga result
     * 
     * @param transactionId The transaction ID
     * @param success       Whether the saga was successful
     * @return Updated Transaction entity
     */
    private Transaction updateTransactionStatus(Long transactionId, boolean success) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new RuntimeException("Transaction not found with id: " + transactionId));

        transaction.setStatus(success ? TransactionStatus.SUCCESS : TransactionStatus.FAILED);
        transaction.setUpdatedDate(Instant.now());

        Transaction updatedTransaction = transactionRepository.save(transaction);
        log.info("Transaction id: {} updated to status: {}", transactionId, updatedTransaction.getStatus());

        return updatedTransaction;
    }

    /**
     * Map Transaction entity to TransactionResponseDTO
     */
    private TransactionResponseDTO mapToResponseDTO(Transaction transaction) {
        return new TransactionResponseDTO(
                transaction.getId(),
                transaction.getDescription(),
                transaction.getSourceWalletId(),
                transaction.getDestinationWalletId(),
                transaction.getAmount(),
                transaction.getStatus(),
                transaction.getType(),
                transaction.getSagaInstanceId(),
                transaction.getCreatedDate(),
                transaction.getUpdatedDate());
    }
}
