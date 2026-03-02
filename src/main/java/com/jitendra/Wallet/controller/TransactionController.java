package com.jitendra.Wallet.controller;

import java.util.List;

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
import jakarta.validation.Valid;
import com.jitendra.Wallet.dto.TransactionRequestDTO;
import com.jitendra.Wallet.dto.TransactionResponseDTO;
import com.jitendra.Wallet.entity.TransactionStatus;
import com.jitendra.Wallet.services.TransactionService;

@RestController
@RequestMapping("/transactions")
@RequiredArgsConstructor
public class TransactionController {
    
    private final TransactionService transactionService;
    
    /**
     * Create a new transaction
     * This will initiate the saga orchestration for the transaction
     * 
     * @param transactionRequest The transaction details
     * @return Created transaction with saga instance ID
     */
    @PostMapping("/create")
    public ResponseEntity<TransactionResponseDTO> createTransaction(@Valid @RequestBody TransactionRequestDTO transactionRequest) {
        TransactionResponseDTO response = transactionService.createTransaction(transactionRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
    
    /**
     * Get transaction by ID
     * 
     * @param id The transaction ID
     * @return Transaction details
     */
    @GetMapping("/{id}")
    public ResponseEntity<TransactionResponseDTO> getTransactionById(@PathVariable Long id) {
        TransactionResponseDTO response = transactionService.getTransactionById(id);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get all transactions for a wallet (both as source and destination)
     * 
     * @param walletId The wallet ID
     * @return List of transactions
     */
    @GetMapping("/wallet/{walletId}")
    public ResponseEntity<List<TransactionResponseDTO>> getTransactionsByWalletId(@PathVariable Long walletId) {
        List<TransactionResponseDTO> response = transactionService.getTransactionsByWalletId(walletId);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get all transactions sent from a wallet
     * 
     * @param sourceWalletId The source wallet ID
     * @return List of transactions
     */
    @GetMapping("/source/{sourceWalletId}")
    public ResponseEntity<List<TransactionResponseDTO>> getTransactionsBySourceWallet(@PathVariable Long sourceWalletId) {
        List<TransactionResponseDTO> response = transactionService.getTransactionsBySourceWallet(sourceWalletId);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get all transactions received at a wallet
     * 
     * @param destinationWalletId The destination wallet ID
     * @return List of transactions
     */
    @GetMapping("/destination/{destinationWalletId}")
    public ResponseEntity<List<TransactionResponseDTO>> getTransactionsByDestinationWallet(@PathVariable Long destinationWalletId) {
        List<TransactionResponseDTO> response = transactionService.getTransactionsByDestinationWallet(destinationWalletId);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get all transactions with a specific status
     * 
     * @param status The transaction status
     * @return List of transactions
     */
    @GetMapping("/status")
    public ResponseEntity<List<TransactionResponseDTO>> getTransactionsByStatus(@RequestParam TransactionStatus status) {
        List<TransactionResponseDTO> response = transactionService.getTransactionsByStatus(status);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get all transactions for a saga instance
     * 
     * @param sagaInstanceId The saga instance ID
     * @return List of transactions
     */
    @GetMapping("/saga/{sagaInstanceId}")
    public ResponseEntity<List<TransactionResponseDTO>> getTransactionsBySagaInstance(@PathVariable Long sagaInstanceId) {
        List<TransactionResponseDTO> response = transactionService.getTransactionsBySagaInstance(sagaInstanceId);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Update transaction status
     * 
     * @param transactionId The transaction ID
     * @param status The new status
     * @return Updated transaction
     */
    @PutMapping("/{transactionId}/status")
    public ResponseEntity<TransactionResponseDTO> updateTransactionStatus(
            @PathVariable Long transactionId,
            @RequestParam TransactionStatus status) {
        TransactionResponseDTO response = transactionService.updateTransactionStatus(transactionId, status);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get transactions between two wallets
     * 
     * @param sourceWalletId The source wallet ID
     * @param destinationWalletId The destination wallet ID
     * @return List of transactions
     */
    @GetMapping("/between")
    public ResponseEntity<List<TransactionResponseDTO>> getTransactionsBetweenWallets(
            @RequestParam Long sourceWalletId,
            @RequestParam Long destinationWalletId) {
        List<TransactionResponseDTO> response = transactionService.getTransactionsBetweenWallets(sourceWalletId, destinationWalletId);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get pending transactions for a saga instance (for compensation/retry)
     * 
     * @param sagaInstanceId The saga instance ID
     * @return List of pending transactions
     */
    @GetMapping("/saga/{sagaInstanceId}/pending")
    public ResponseEntity<List<TransactionResponseDTO>> getPendingTransactionsBySagaInstance(@PathVariable Long sagaInstanceId) {
        List<TransactionResponseDTO> response = transactionService.getPendingTransactionsBySagaInstance(sagaInstanceId);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get successful transactions for a wallet
     * 
     * @param walletId The wallet ID
     * @return List of successful transactions
     */
    @GetMapping("/wallet/{walletId}/successful")
    public ResponseEntity<List<TransactionResponseDTO>> getSuccessfulTransactionsByWallet(@PathVariable Long walletId) {
        List<TransactionResponseDTO> response = transactionService.getSuccessfulTransactionsByWallet(walletId);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get failed transactions for a wallet
     * 
     * @param walletId The wallet ID
     * @return List of failed transactions
     */
    @GetMapping("/wallet/{walletId}/failed")
    public ResponseEntity<List<TransactionResponseDTO>> getFailedTransactionsByWallet(@PathVariable Long walletId) {
        List<TransactionResponseDTO> response = transactionService.getFailedTransactionsByWallet(walletId);
        return ResponseEntity.ok(response);
    }
}
