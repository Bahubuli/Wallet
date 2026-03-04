package com.jitendra.Wallet.controller;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import com.jitendra.Wallet.dto.TransactionRequestDTO;
import com.jitendra.Wallet.dto.TransactionResponseDTO;
import com.jitendra.Wallet.entity.TransactionStatus;
import com.jitendra.Wallet.services.TransactionService;

/**
 * TransactionController — all list endpoints are paginated.
 *
 * HOW PAGINATION WORKS IN THE CONTROLLER:
 * ----------------------------------------
 * Spring MVC automatically reads ?page, ?size, and ?sort from the URL and
 * populates a Pageable object for us. We use @PageableDefault to set sensible
 * defaults so callers don't HAVE to pass pagination params — the endpoint
 * still works as before, but now caps at 20 results instead of returning
 * everything.
 *
 * @PageableDefault(size = 20, sort = "createdDate", direction = DESC)
 *                       ↑ If the caller passes nothing, we use these. If they
 *                       DO pass params,
 *                       their values override these defaults.
 *
 *                       CALLER INTERFACE:
 *                       GET /transactions/wallet/42 → page 0, size 20, sorted
 *                       by createdDate DESC
 *                       GET /transactions/wallet/42?size=5&page=1 → page 1,
 *                       size 5, sorted by createdDate DESC
 *                       GET /transactions/wallet/42?sort=amount,asc → page 0,
 *                       size 20, sorted by amount ASC
 *
 *                       RESPONSE SHAPE (Page<T>):
 *                       {
 *                       "content": [...], ← the actual records
 *                       "totalElements": 47, ← how many records match in total
 *                       "totalPages": 3, ← ceil(47 / 20)
 *                       "number": 0, ← current page (0-based)
 *                       "size": 20, ← page size used
 *                       "first": true, ← convenience boolean
 *                       "last": false ← convenience boolean
 *                       }
 */
@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
public class TransactionController {

    private final TransactionService transactionService;

    // -------------------------------------------------------------------------
    // WRITE / SINGLE-RECORD OPERATIONS — no pagination
    // -------------------------------------------------------------------------

    /**
     * POST /transactions/create
     * Creates a transaction and starts saga orchestration.
     * Returns a single TransactionResponseDTO — no pagination needed.
     */
    @PostMapping("/create")
    public ResponseEntity<TransactionResponseDTO> createTransaction(
            @Valid @RequestBody TransactionRequestDTO transactionRequest) {
        TransactionResponseDTO response = transactionService.createTransaction(transactionRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * GET /transactions/{id}
     * Fetches one specific transaction by ID — no list, no pagination.
     */
    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponseDTO> getTransactionById(@PathVariable Long id) {
        TransactionResponseDTO response = transactionService.getTransactionById(id);
        return ResponseEntity.ok(response);
    }

    /**
     * PUT /transactions/{transactionId}/status?status=SUCCESS
     * Updates a single transaction's status — no pagination.
     */
    @PutMapping("/{transactionId}/status")
    public ResponseEntity<TransactionResponseDTO> updateTransactionStatus(
            @PathVariable Long transactionId,
            @RequestParam TransactionStatus status) {
        TransactionResponseDTO response = transactionService.updateTransactionStatus(transactionId, status);
        return ResponseEntity.ok(response);
    }

    // -------------------------------------------------------------------------
    // PAGINATED LIST ENDPOINTS
    //
    // Each method adds a Pageable parameter (auto-populated by Spring MVC from
    // query params) and returns ResponseEntity<Page<TransactionResponseDTO>>.
    //
    // The Page wrapper tells the client how many items exist in total and how
    // many pages there are — without it, the caller can't know when to stop
    // requesting more pages.
    // -------------------------------------------------------------------------

    /**
     * GET /transactions/wallet/{walletId}
     * All transactions where this wallet is source OR destination.
     * Default: page 0, 20 per page, ordered newest-first.
     */
    @GetMapping("/wallet/{walletId}")
    public ResponseEntity<Page<TransactionResponseDTO>> getTransactionsByWalletId(
            @PathVariable Long walletId,
            @PageableDefault(size = 20, sort = "createdDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(transactionService.getTransactionsByWalletId(walletId, pageable));
    }

    /**
     * GET /transactions/source/{sourceWalletId}
     * All transactions sent FROM a wallet.
     */
    @GetMapping("/source/{sourceWalletId}")
    public ResponseEntity<Page<TransactionResponseDTO>> getTransactionsBySourceWallet(
            @PathVariable Long sourceWalletId,
            @PageableDefault(size = 20, sort = "createdDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(transactionService.getTransactionsBySourceWallet(sourceWalletId, pageable));
    }

    /**
     * GET /transactions/destination/{destinationWalletId}
     * All transactions received AT a wallet.
     */
    @GetMapping("/destination/{destinationWalletId}")
    public ResponseEntity<Page<TransactionResponseDTO>> getTransactionsByDestinationWallet(
            @PathVariable Long destinationWalletId,
            @PageableDefault(size = 20, sort = "createdDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(
                transactionService.getTransactionsByDestinationWallet(destinationWalletId, pageable));
    }

    /**
     * GET /transactions/status?status=PENDING
     * All transactions in a given status, e.g. for monitoring dashboards.
     */
    @GetMapping("/status")
    public ResponseEntity<Page<TransactionResponseDTO>> getTransactionsByStatus(
            @RequestParam TransactionStatus status,
            @PageableDefault(size = 20, sort = "createdDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(transactionService.getTransactionsByStatus(status, pageable));
    }

    /**
     * GET /transactions/saga/{sagaInstanceId}
     * All transactions for a given saga — useful for auditing/debugging a saga run.
     *
     * NOTE: This is the public HTTP endpoint. It is NOT the saga-internal lookup.
     * The saga compensation code bypasses this controller entirely and queries
     * the repository directly without pagination.
     */
    @GetMapping("/saga/{sagaInstanceId}")
    public ResponseEntity<Page<TransactionResponseDTO>> getTransactionsBySagaInstance(
            @PathVariable Long sagaInstanceId,
            @PageableDefault(size = 20, sort = "createdDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(transactionService.getTransactionsBySagaInstance(sagaInstanceId, pageable));
    }

    /**
     * GET /transactions/saga/{sagaInstanceId}/pending
     * Returns PENDING transactions for a saga — used to monitor stuck sagas.
     * This endpoint uses the NON-PAGINATED service method intentionally because:
     * 1. Pending saga transactions are few (≤ saga step count ≈ 5)
     * 2. Breaking this into pages would complicate clients that need the full list
     */
    @GetMapping("/saga/{sagaInstanceId}/pending")
    public ResponseEntity<java.util.List<TransactionResponseDTO>> getPendingTransactionsBySagaInstance(
            @PathVariable Long sagaInstanceId) {
        return ResponseEntity.ok(transactionService.getPendingTransactionsBySagaInstance(sagaInstanceId));
    }

    /**
     * GET /transactions/between?sourceWalletId=1&destinationWalletId=2
     * Transactions between two specific wallets — useful for transfer history.
     */
    @GetMapping("/between")
    public ResponseEntity<Page<TransactionResponseDTO>> getTransactionsBetweenWallets(
            @RequestParam Long sourceWalletId,
            @RequestParam Long destinationWalletId,
            @PageableDefault(size = 20, sort = "createdDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(
                transactionService.getTransactionsBetweenWallets(sourceWalletId, destinationWalletId, pageable));
    }

    /**
     * GET /transactions/wallet/{walletId}/successful
     * Only SUCCESS transactions for a wallet — DB-filtered, then paginated.
     */
    @GetMapping("/wallet/{walletId}/successful")
    public ResponseEntity<Page<TransactionResponseDTO>> getSuccessfulTransactionsByWallet(
            @PathVariable Long walletId,
            @PageableDefault(size = 20, sort = "createdDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(transactionService.getSuccessfulTransactionsByWallet(walletId, pageable));
    }

    /**
     * GET /transactions/wallet/{walletId}/failed
     * Only FAILED transactions — useful for failure monitoring and retry logic.
     * GET /wallet/10/failed?page=2&size=5&sort=amount,asc
     */
    @GetMapping("/wallet/{walletId}/failed")
    public ResponseEntity<Page<TransactionResponseDTO>> getFailedTransactionsByWallet(
            @PathVariable Long walletId,
            @PageableDefault(size = 20, sort = "createdDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return ResponseEntity.ok(transactionService.getFailedTransactionsByWallet(walletId, pageable));
    }
}
