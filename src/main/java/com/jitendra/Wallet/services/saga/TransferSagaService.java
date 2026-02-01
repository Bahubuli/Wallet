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
import com.jitendra.Wallet.repository.WalletRepository;
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
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    /**
     * Initiates a wallet transfer saga.
     * Creates Transaction, validates wallets, starts saga, and executes all steps.
     * 
     * @param transactionRequest The transfer request details
     * @return TransactionResponseDTO with transaction and saga details
     */
    @Transactional
    public TransactionResponseDTO initiateTransfer(TransactionRequestDTO transactionRequest) {
        log.info("Initiating transfer saga from wallet {} to wallet {} with amount {}",
                transactionRequest.getSourceWalletId(),
                transactionRequest.getDestinationWalletId(),
                transactionRequest.getAmount());

        // Validate source wallet exists
        if (!walletRepository.existsById(transactionRequest.getSourceWalletId())) {
            throw new RuntimeException("Source wallet not found with id: " + transactionRequest.getSourceWalletId());
        }

        // Validate destination wallet exists
        if (!walletRepository.existsById(transactionRequest.getDestinationWalletId())) {
            throw new RuntimeException(
                    "Destination wallet not found with id: " + transactionRequest.getDestinationWalletId());
        }

        // Create Transaction with PENDING status using builder pattern
        Instant now = Instant.now();
        Transaction transaction = Transaction.builder()
                .description(transactionRequest.getDescription())
                .sourceWalletId(transactionRequest.getSourceWalletId())
                .destinationWalletId(transactionRequest.getDestinationWalletId())
                .amount(transactionRequest.getAmount())
                .type(transactionRequest.getType())
                .status(TransactionStatus.PENDING)
                .createdDate(now)
                .updatedDate(now)
                .build();

        Transaction savedTransaction = transactionRepository.save(transaction);
        log.info("Transaction created with id: {} in PENDING status", savedTransaction.getId());

        // Build saga context with transfer data using builder pattern
        Map<String, Object> contextData = new HashMap<>();
        contextData.put("sourceWalletId", transactionRequest.getSourceWalletId());
        contextData.put("destinationWalletId", transactionRequest.getDestinationWalletId());
        contextData.put("amount", transactionRequest.getAmount());
        contextData.put("description",
                transactionRequest.getDescription() != null ? transactionRequest.getDescription() : "");
        contextData.put("transactionType", transactionRequest.getType());
        contextData.put("transactionId", savedTransaction.getId());

        SagaContext sagaContext = SagaContext.builder()
                .sagaType(SagaType.TRANSACTION_TRANSFER.name())
                .data(contextData)
                .build();

        // Start the saga (creates saga instance in DB)
        Long sagaInstanceId = sagaOrchestrator.startSaga(sagaContext);
        sagaContext.setSagaInstanceId(sagaInstanceId);

        // Link transaction to saga
        savedTransaction.setSagaInstanceId(sagaInstanceId);
        savedTransaction = transactionRepository.save(savedTransaction);

        log.info("Saga started with id: {}, linked to transaction id: {}", sagaInstanceId, savedTransaction.getId());

        // Execute the saga steps and get final status
        boolean success = executeTransferSaga(sagaInstanceId, sagaContext);

        // Update transaction status based on saga result
        savedTransaction = updateTransactionStatus(savedTransaction.getId(), success);

        return mapToResponseDTO(savedTransaction);
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

        for (SagaStepType stepType : steps) {
            String stepName = stepType.name();
            log.info("Executing saga step: {} for sagaInstanceId: {}", stepName, sagaInstanceId);

            try {
                boolean stepResult = sagaOrchestrator.executeStep(sagaInstanceId, stepName);

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
