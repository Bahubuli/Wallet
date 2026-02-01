package com.jitendra.Wallet.controller;

import java.math.BigDecimal;
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
    public ResponseEntity<WalletResponseDTO> createWallet(@RequestBody WalletRequestDTO walletRequest) {
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
     * Get all wallets for a user
     * GET /wallets/user/{userId}
     */
    @GetMapping("/user/{userId}")
    public ResponseEntity<List<WalletResponseDTO>> getWalletsByUserId(@PathVariable Long userId) {
        List<WalletResponseDTO> response = walletService.getWalletsByUserId(userId);
        return ResponseEntity.ok(response);
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
