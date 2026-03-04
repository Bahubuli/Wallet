package com.jitendra.Wallet.services;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jitendra.Wallet.dto.TransactionRequestDTO;
import com.jitendra.Wallet.dto.TransactionResponseDTO;
import com.jitendra.Wallet.entity.Transaction;
import com.jitendra.Wallet.entity.TransactionStatus;
import com.jitendra.Wallet.entity.Wallet;
import com.jitendra.Wallet.exception.BusinessException;
import com.jitendra.Wallet.exception.ResourceNotFoundException;
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

        // =====================================================================
        // SINGLE-RECORD OPERATIONS — no pagination
        // =====================================================================

        /**
         * Creates a transaction and initiates saga orchestration.
         * This is a write operation — pagination is irrelevant.
         */
        public TransactionResponseDTO createTransaction(TransactionRequestDTO transactionRequest) {
                log.info("Creating transaction from wallet {} to wallet {} with amount {}",
                                transactionRequest.getSourceWalletId(),
                                transactionRequest.getDestinationWalletId(),
                                transactionRequest.getAmount());

                Wallet sourceWallet = walletRepository.findById(transactionRequest.getSourceWalletId())
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Source wallet not found with id: "
                                                                + transactionRequest.getSourceWalletId()));

                if (!walletRepository.existsById(transactionRequest.getDestinationWalletId())) {
                        throw new ResourceNotFoundException(
                                        "Destination wallet not found with id: "
                                                        + transactionRequest.getDestinationWalletId());
                }

                if (!sourceWallet.hasSufficientBalance(transactionRequest.getAmount())) {
                        throw new BusinessException("Insufficient balance in source wallet");
                }

                return transferSagaService.initiateTransfer(transactionRequest);
        }

        /**
         * Fetches one transaction by its ID — no list, no pagination.
         */
        public TransactionResponseDTO getTransactionById(Long id) {
                log.info("Fetching transaction with id: {}", id);
                Transaction transaction = transactionRepository.findById(id)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Transaction not found with id: " + id));
                return mapToResponseDTO(transaction);
        }

        /**
         * Updates a transaction's status — single-record mutation, no pagination.
         */
        @Transactional
        public TransactionResponseDTO updateTransactionStatus(Long transactionId, TransactionStatus status) {
                log.info("Updating transaction id: {} with status: {}", transactionId, status);
                Transaction transaction = transactionRepository.findById(transactionId)
                                .orElseThrow(() -> new ResourceNotFoundException(
                                                "Transaction not found with id: " + transactionId));

                transaction.setStatus(status);
                transaction.setUpdatedDate(Instant.now());
                Transaction updatedTransaction = transactionRepository.save(transaction);

                log.info("Transaction id: {} updated with status: {}", transactionId, status);
                return mapToResponseDTO(updatedTransaction);
        }

        // =====================================================================
        // PAGINATED PUBLIC API METHODS
        //
        // WHY PAGINATE:
        // A busy wallet can have thousands of transactions. Without pagination,
        // GET /transactions/wallet/42 would load all of them into memory every time.
        // With Pageable, the repository generates:
        // SELECT ... WHERE ... LIMIT :size OFFSET :page*size ← row fetch
        // SELECT COUNT(*) WHERE ... ← for metadata
        // So only the requested "window" ever leaves the database.
        //
        // HOW page.map() WORKS:
        // The repository returns Page<Transaction> (an entity page).
        // We need to present DTOs, not entities, to the outside world.
        // Page.map(fn) applies fn to every item in the page's content list
        // and returns a new Page<DTO> with all pagination metadata preserved.
        // It's the idiomatic, single-line way to convert Page<Entity> → Page<DTO>.
        //
        // CALLER INTERFACE:
        // GET /transactions/wallet/42?page=0&size=10&sort=createdDate,desc
        // Spring MVC auto-populates the Pageable from query params.
        // =====================================================================

        /**
         * GET /transactions/wallet/{walletId} — transactions where wallet is src OR dst
         */
        public Page<TransactionResponseDTO> getTransactionsByWalletId(Long walletId, Pageable pageable) {
                log.info("Fetching transactions for wallet id: {} (page {})", walletId, pageable.getPageNumber());
                return transactionRepository.findByWalletId(walletId, pageable)
                                .map(this::mapToResponseDTO);
        }

        /** GET /transactions/source/{sourceWalletId} */
        public Page<TransactionResponseDTO> getTransactionsBySourceWallet(Long sourceWalletId, Pageable pageable) {
                log.info("Fetching transactions from source wallet id: {}", sourceWalletId);
                return transactionRepository.findBySourceWalletId(sourceWalletId, pageable)
                                .map(this::mapToResponseDTO);
        }

        /** GET /transactions/destination/{destinationWalletId} */
        public Page<TransactionResponseDTO> getTransactionsByDestinationWallet(Long destinationWalletId,
                        Pageable pageable) {
                log.info("Fetching transactions to destination wallet id: {}", destinationWalletId);
                return transactionRepository.findByDestinationWalletId(destinationWalletId, pageable)
                                .map(this::mapToResponseDTO);
        }

        /** GET /transactions/status?status=PENDING */
        public Page<TransactionResponseDTO> getTransactionsByStatus(TransactionStatus status, Pageable pageable) {
                log.info("Fetching transactions with status: {}", status);
                return transactionRepository.findByStatus(status.name(), pageable)
                                .map(this::mapToResponseDTO);
        }

        /**
         * GET /transactions/saga/{sagaInstanceId} — public endpoint.
         *
         * IMPORTANT: This is NOT the same call used by the saga orchestrator.
         * The orchestrator calls findBySagaInstanceId() directly on the repo
         * as a plain List — unpaginated — because it needs ALL saga steps for
         * compensation. This method is only for callers querying the HTTP API.
         */
        public Page<TransactionResponseDTO> getTransactionsBySagaInstance(Long sagaInstanceId, Pageable pageable) {
                log.info("Fetching transactions for saga instance id: {}", sagaInstanceId);
                return transactionRepository.findBySagaInstanceId(sagaInstanceId, pageable)
                                .map(this::mapToResponseDTO);
        }

        /** GET /transactions/between?sourceWalletId=1&destinationWalletId=2 */
        public Page<TransactionResponseDTO> getTransactionsBetweenWallets(
                        Long sourceWalletId, Long destinationWalletId, Pageable pageable) {
                log.info("Fetching transactions between wallets {} and {}", sourceWalletId, destinationWalletId);
                return transactionRepository
                                .findBySourceWalletIdAndDestinationWalletId(sourceWalletId, destinationWalletId,
                                                pageable)
                                .map(this::mapToResponseDTO);
        }

        /**
         * GET /transactions/wallet/{walletId}/successful
         *
         * WHY WE REPLACED THE OLD APPROACH:
         * Previously: findByWalletId (all rows) → filter in Java → .toList()
         * That means if wallet has 50,000 transactions and only 100 are SUCCESS,
         * we still loaded all 50,000 from the DB. Pure waste.
         *
         * Now: a single DB query with WHERE (src=? OR dst=?) AND status='SUCCESS'
         * plus LIMIT/OFFSET. Only SUCCESS rows ever leave the DB at all.
         */
        public Page<TransactionResponseDTO> getSuccessfulTransactionsByWallet(Long walletId, Pageable pageable) {
                log.info("Fetching successful transactions for wallet id: {}", walletId);
                return transactionRepository.findByWalletIdAndStatus(walletId, TransactionStatus.SUCCESS, pageable)
                                .map(this::mapToResponseDTO);
        }

        /**
         * GET /transactions/wallet/{walletId}/failed — same reasoning as above.
         */
        public Page<TransactionResponseDTO> getFailedTransactionsByWallet(Long walletId, Pageable pageable) {
                log.info("Fetching failed transactions for wallet id: {}", walletId);
                return transactionRepository.findByWalletIdAndStatus(walletId, TransactionStatus.FAILED, pageable)
                                .map(this::mapToResponseDTO);
        }

        // =====================================================================
        // NON-PAGINATED SAGA-INTERNAL METHOD
        //
        // WHY NOT PAGINATED:
        // The saga orchestrator calls this during compensation to find all
        // transactions that are still PENDING. There are at most a handful per
        // saga — paginating them would risk only seeing the first "page" and
        // silently leaving the rest uncompensated (data corruption).
        //
        // This method is called by the saga, NOT by any HTTP controller.
        // It does not appear in TransactionController at all.
        // =====================================================================

        public List<TransactionResponseDTO> getPendingTransactionsBySagaInstance(Long sagaInstanceId) {
                log.info("Fetching pending transactions for saga instance id: {}", sagaInstanceId);
                return transactionRepository
                                .findBySagaInstanceIdAndStatus(sagaInstanceId, TransactionStatus.PENDING.name())
                                .stream()
                                .map(this::mapToResponseDTO)
                                .toList();
        }

        // =====================================================================
        // Helper
        // =====================================================================

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
