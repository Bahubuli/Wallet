package com.jitendra.Wallet.services;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jitendra.Wallet.dto.TransactionRequestDTO;
import com.jitendra.Wallet.dto.TransactionResponseDTO;
import com.jitendra.Wallet.entity.Transaction;
import com.jitendra.Wallet.entity.TransactionStatus;
import com.jitendra.Wallet.entity.Wallet;
import com.jitendra.Wallet.repository.TransactionRepository;
import com.jitendra.Wallet.repository.WalletRepository;
import com.jitendra.Wallet.services.saga.TransferSagaService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class TransactionService {

        private final TransactionRepository transactionRepository;
        private final WalletRepository walletRepository;
        private final TransferSagaService transferSagaService;

        /**
         * Create a new transaction and initiate saga orchestration
         * This method handles the complete transaction flow using the saga pattern
         * 
         * @param transactionRequest The transaction details
         * @return TransactionResponseDTO with transaction details including saga
         *         instance ID
         */
        public TransactionResponseDTO createTransaction(TransactionRequestDTO transactionRequest) {
                log.info("Creating transaction from wallet {} to wallet {} with amount {}",
                                transactionRequest.getSourceWalletId(),
                                transactionRequest.getDestinationWalletId(),
                                transactionRequest.getAmount());

                // Prevent self-transfer
                if (transactionRequest.getSourceWalletId().equals(transactionRequest.getDestinationWalletId())) {
                        throw new IllegalArgumentException("Source and destination wallets must be different");
                }

                // Validate wallets exist
                Wallet sourceWallet = walletRepository.findById(transactionRequest.getSourceWalletId())
                                .orElseThrow(() -> new RuntimeException(
                                                "Source wallet not found with id: "
                                                                + transactionRequest.getSourceWalletId()));

                // Validate destination wallet exists
                if (!walletRepository.existsById(transactionRequest.getDestinationWalletId())) {
                        throw new RuntimeException(
                                        "Destination wallet not found with id: "
                                                        + transactionRequest.getDestinationWalletId());
                }

                // Validate sufficient balance
                if (!sourceWallet.hasSufficientBalance(transactionRequest.getAmount())) {
                        throw new RuntimeException("Insufficient balance in source wallet");
                }

                // Delegate to TransferSagaService which creates Transaction and executes saga
                return transferSagaService.initiateTransfer(transactionRequest);
        }

        /**
         * Get transaction by ID
         * 
         * @param id The transaction ID
         * @return TransactionResponseDTO with transaction details
         */
        public TransactionResponseDTO getTransactionById(Long id) {
                log.info("Fetching transaction with id: {}", id);
                Transaction transaction = transactionRepository.findById(id)
                                .orElseThrow(() -> new RuntimeException("Transaction not found with id: " + id));
                return mapToResponseDTO(transaction);
        }

        /**
         * Get all transactions for a wallet (both as source and destination)
         * 
         * @param walletId The wallet ID
         * @return List of TransactionResponseDTO
         */
        public List<TransactionResponseDTO> getTransactionsByWalletId(Long walletId) {
                log.info("Fetching transactions for wallet id: {}", walletId);
                List<Transaction> transactions = transactionRepository.findByWalletId(walletId);
                return transactions.stream()
                                .map(this::mapToResponseDTO)
                                .toList();
        }

        /**
         * Get all transactions sent from a specific wallet
         * 
         * @param sourceWalletId The source wallet ID
         * @return List of TransactionResponseDTO
         */
        public List<TransactionResponseDTO> getTransactionsBySourceWallet(Long sourceWalletId) {
                log.info("Fetching transactions from source wallet id: {}", sourceWalletId);
                List<Transaction> transactions = transactionRepository.findBySourceWalletId(sourceWalletId);
                return transactions.stream()
                                .map(this::mapToResponseDTO)
                                .toList();
        }

        /**
         * Get all transactions received at a specific wallet
         * 
         * @param destinationWalletId The destination wallet ID
         * @return List of TransactionResponseDTO
         */
        public List<TransactionResponseDTO> getTransactionsByDestinationWallet(Long destinationWalletId) {
                log.info("Fetching transactions to destination wallet id: {}", destinationWalletId);
                List<Transaction> transactions = transactionRepository.findByDestinationWalletId(destinationWalletId);
                return transactions.stream()
                                .map(this::mapToResponseDTO)
                                .toList();
        }

        /**
         * Get all transactions with a specific status
         * 
         * @param status The transaction status
         * @return List of TransactionResponseDTO
         */
        public List<TransactionResponseDTO> getTransactionsByStatus(TransactionStatus status) {
                log.info("Fetching transactions with status: {}", status);
                List<Transaction> transactions = transactionRepository.findByStatus(status.name());
                return transactions.stream()
                                .map(this::mapToResponseDTO)
                                .toList();
        }

        /**
         * Get all transactions for a specific saga instance
         * 
         * @param sagaInstanceId The saga instance ID
         * @return List of TransactionResponseDTO
         */
        public List<TransactionResponseDTO> getTransactionsBySagaInstance(Long sagaInstanceId) {
                log.info("Fetching transactions for saga instance id: {}", sagaInstanceId);
                List<Transaction> transactions = transactionRepository.findBySagaInstanceId(sagaInstanceId);
                return transactions.stream()
                                .map(this::mapToResponseDTO)
                                .toList();
        }

        /**
         * Update transaction status (used by saga orchestrator during compensation or
         * completion)
         * 
         * @param transactionId The transaction ID
         * @param status        The new transaction status
         * @return TransactionResponseDTO with updated transaction details
         */
        @Transactional
        public TransactionResponseDTO updateTransactionStatus(Long transactionId, TransactionStatus status) {
                log.info("Updating transaction id: {} with status: {}", transactionId, status);
                Transaction transaction = transactionRepository.findById(transactionId)
                                .orElseThrow(() -> new RuntimeException(
                                                "Transaction not found with id: " + transactionId));

                transaction.setStatus(status);
                transaction.setUpdatedDate(Instant.now());
                Transaction updatedTransaction = transactionRepository.save(transaction);

                log.info("Transaction id: {} updated with status: {}", transactionId, status);
                return mapToResponseDTO(updatedTransaction);
        }

        /**
         * Get all transactions between two wallets
         * 
         * @param sourceWalletId      The source wallet ID
         * @param destinationWalletId The destination wallet ID
         * @return List of TransactionResponseDTO
         */
        public List<TransactionResponseDTO> getTransactionsBetweenWallets(Long sourceWalletId,
                        Long destinationWalletId) {
                log.info("Fetching transactions between source wallet id: {} and destination wallet id: {}",
                                sourceWalletId, destinationWalletId);
                List<Transaction> transactions = transactionRepository
                                .findBySourceWalletIdAndDestinationWalletId(sourceWalletId, destinationWalletId);
                return transactions.stream()
                                .map(this::mapToResponseDTO)
                                .toList();
        }

        /**
         * Get pending transactions for saga compensation/retry
         * 
         * @param sagaInstanceId The saga instance ID
         * @return List of TransactionResponseDTO with PENDING status
         */
        public List<TransactionResponseDTO> getPendingTransactionsBySagaInstance(Long sagaInstanceId) {
                log.info("Fetching pending transactions for saga instance id: {}", sagaInstanceId);
                List<Transaction> transactions = transactionRepository.findBySagaInstanceIdAndStatus(sagaInstanceId,
                                TransactionStatus.PENDING.name());
                return transactions.stream()
                                .map(this::mapToResponseDTO)
                                .toList();
        }

        /**
         * Get successful transactions for a wallet
         * 
         * @param walletId The wallet ID
         * @return List of successful TransactionResponseDTO
         */
        public List<TransactionResponseDTO> getSuccessfulTransactionsByWallet(Long walletId) {
                log.info("Fetching successful transactions for wallet id: {}", walletId);
                List<Transaction> transactions = transactionRepository.findByWalletId(walletId).stream()
                                .filter(t -> t.getStatus() == TransactionStatus.SUCCESS)
                                .toList();
                return transactions.stream()
                                .map(this::mapToResponseDTO)
                                .toList();
        }

        /**
         * Get failed transactions for a wallet
         * 
         * @param walletId The wallet ID
         * @return List of failed TransactionResponseDTO
         */
        public List<TransactionResponseDTO> getFailedTransactionsByWallet(Long walletId) {
                log.info("Fetching failed transactions for wallet id: {}", walletId);
                List<Transaction> transactions = transactionRepository.findByWalletId(walletId).stream()
                                .filter(t -> t.getStatus() == TransactionStatus.FAILED)
                                .toList();
                return transactions.stream()
                                .map(this::mapToResponseDTO)
                                .toList();
        }

        /**
         * Helper method to map Transaction entity to TransactionResponseDTO
         * 
         * @param transaction The transaction entity
         * @return TransactionResponseDTO
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
