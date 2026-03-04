package com.jitendra.Wallet.controller;

import java.math.BigDecimal;

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

import com.jitendra.Wallet.dto.WalletRequestDTO;
import com.jitendra.Wallet.dto.WalletResponseDTO;
import com.jitendra.Wallet.services.WalletService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/wallets")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    /**
     * Create a new wallet
     * POST /wallets/create
     */
    @PostMapping("/create")
    public ResponseEntity<WalletResponseDTO> createWallet(@Valid @RequestBody WalletRequestDTO walletRequest) {
        WalletResponseDTO response = walletService.createWallet(walletRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get wallet by ID
     * GET /wallets/{id}
     */
    @GetMapping("/{id}")
    public ResponseEntity<WalletResponseDTO> getWalletById(@PathVariable Long id) {
        WalletResponseDTO response = walletService.getWalletById(id);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /wallets/user/{userId}
     * Returns all wallets belonging to a user — paginated.
     *
     * WHY PAGINATED: A user could have many wallets. Returning all of them
     * on every request would get expensive. With Pageable, the DB applies
     * LIMIT/OFFSET before sending data back to the app.
     *
     * EXAMPLE CALLS:
     * GET /wallets/user/1 → first 10 wallets
     * GET /wallets/user/1?page=1&size=10 → next 10
     * GET /wallets/user/1?sort=balance,desc → sorted by balance
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<Page<WalletResponseDTO>> getWalletsByUserId(
            @PathVariable Long userId,
            @PageableDefault(size = 10, sort = "id", direction = Sort.Direction.ASC) Pageable pageable) {
        return ResponseEntity.ok(walletService.getWalletsByUserId(userId, pageable));
    }

    /**
     * Activate a wallet
     * PUT /wallets/{id}/activate
     */
    @PutMapping("/{id}/activate")
    public ResponseEntity<WalletResponseDTO> activateWallet(@PathVariable Long id) {
        WalletResponseDTO response = walletService.activateWallet(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Deactivate a wallet
     * PUT /wallets/{id}/deactivate
     */
    @PutMapping("/{id}/deactivate")
    public ResponseEntity<WalletResponseDTO> deactivateWallet(@PathVariable Long id) {
        WalletResponseDTO response = walletService.deactivateWallet(id);
        return ResponseEntity.ok(response);
    }

    /**
     * Get wallet balance
     * GET /wallets/{id}/balance
     */
    @GetMapping("/{id}/balance")
    public ResponseEntity<BigDecimal> getBalance(@PathVariable Long id) {
        BigDecimal balance = walletService.getBalance(id);
        return ResponseEntity.ok(balance);
    }

    /**
     * Add funds to wallet
     * POST /wallets/{id}/add-funds?amount=100.00
     */
    @PostMapping("/{id}/add-funds")
    public ResponseEntity<WalletResponseDTO> addFunds(
            @PathVariable Long id,
            @RequestParam BigDecimal amount) {
        WalletResponseDTO response = walletService.addFunds(id, amount);
        return ResponseEntity.ok(response);
    }
}
