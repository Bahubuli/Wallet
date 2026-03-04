package com.jitendra.Wallet.services;

import java.math.BigDecimal;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jitendra.Wallet.dto.WalletRequestDTO;
import com.jitendra.Wallet.dto.WalletResponseDTO;
import com.jitendra.Wallet.entity.Wallet;
import com.jitendra.Wallet.exception.BusinessException;
import com.jitendra.Wallet.exception.ResourceNotFoundException;
import com.jitendra.Wallet.repository.UserRepository;
import com.jitendra.Wallet.repository.WalletRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class WalletService {

    private final WalletRepository walletRepository;
    private final UserRepository userRepository;

    /**
     * Create a new wallet for a user
     * 
     * @param walletRequest The wallet creation request
     * @return WalletResponseDTO with created wallet details
     */
    @Transactional
    public WalletResponseDTO createWallet(WalletRequestDTO walletRequest) {
        log.info("Creating wallet for user id: {}", walletRequest.getUserId());

        // BUG-06 fix: validate user exists before creating wallet
        if (!userRepository.existsById(walletRequest.getUserId())) {
            throw new ResourceNotFoundException("User not found with id: " + walletRequest.getUserId());
        }

        Wallet wallet = new Wallet();
        wallet.setUserId(walletRequest.getUserId());
        wallet.setIsActive(walletRequest.getIsActive() != null ? walletRequest.getIsActive() : true);
        wallet.setBalance(walletRequest.getBalance() != null ? walletRequest.getBalance() : BigDecimal.ZERO);

        Wallet savedWallet = walletRepository.save(wallet);
        log.info("Wallet created successfully with id: {} for user id: {}", savedWallet.getId(),
                savedWallet.getUserId());

        return mapToResponseDTO(savedWallet);
    }

    /**
     * Get wallet by ID
     * 
     * @param id The wallet ID
     * @return WalletResponseDTO with wallet details
     */
    public WalletResponseDTO getWalletById(Long id) {
        log.info("Fetching wallet with id: {}", id);
        Wallet wallet = walletRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found with id: " + id));
        return mapToResponseDTO(wallet);
    }

    /**
     * Get all wallets for a user
     * 
     * @param userId The user ID
     * @return List of WalletResponseDTO
     */
    /**
     * GET /wallets/user/{userId}
     *
     * Returns a Page of wallets for a user — paginated because in a large system
     * a user could have many wallets. Without pagination, every call would load
     * all of them into memory. With Pageable the DB applies LIMIT/OFFSET.
     *
     * The non-paginated overload in the repository is kept for internal use
     * (e.g. checking if any wallets exist for a user in validation logic).
     */
    public Page<WalletResponseDTO> getWalletsByUserId(Long userId, Pageable pageable) {
        log.info("Fetching wallets for user id: {} (page {})", userId, pageable.getPageNumber());
        return walletRepository.findByUserId(userId, pageable)
                .map(this::mapToResponseDTO);
    }

    /**
     * Activate a wallet
     * 
     * @param id The wallet ID
     * @return WalletResponseDTO with updated wallet details
     */
    @Transactional
    public WalletResponseDTO activateWallet(Long id) {
        log.info("Activating wallet with id: {}", id);
        Wallet wallet = walletRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found with id: " + id));

        wallet.setIsActive(true);
        Wallet savedWallet = walletRepository.save(wallet);
        log.info("Wallet id: {} activated successfully", id);

        return mapToResponseDTO(savedWallet);
    }

    /**
     * Deactivate a wallet
     * 
     * @param id The wallet ID
     * @return WalletResponseDTO with updated wallet details
     */
    @Transactional
    public WalletResponseDTO deactivateWallet(Long id) {
        log.info("Deactivating wallet with id: {}", id);
        Wallet wallet = walletRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found with id: " + id));

        wallet.setIsActive(false);
        Wallet savedWallet = walletRepository.save(wallet);
        log.info("Wallet id: {} deactivated successfully", id);

        return mapToResponseDTO(savedWallet);
    }

    /**
     * Get wallet balance
     * 
     * @param id The wallet ID
     * @return The current wallet balance
     */
    public BigDecimal getBalance(Long id) {
        log.info("Fetching balance for wallet id: {}", id);
        Wallet wallet = walletRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found with id: " + id));
        return wallet.getBalance();
    }

    /**
     * Add funds to wallet (external deposit)
     * 
     * @param id     The wallet ID
     * @param amount The amount to add
     * @return WalletResponseDTO with updated wallet details
     */
    @Transactional
    public WalletResponseDTO addFunds(Long id, BigDecimal amount) {
        log.info("Adding funds {} to wallet id: {}", amount, id);

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Amount must be positive");
        }

        Wallet wallet = walletRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found with id: " + id));

        if (!wallet.getIsActive()) {
            throw new BusinessException("Cannot add funds to inactive wallet");
        }

        wallet.credit(amount);
        Wallet savedWallet = walletRepository.save(wallet);
        log.info("Added {} to wallet id: {}. New balance: {}", amount, id, savedWallet.getBalance());

        return mapToResponseDTO(savedWallet);
    }

    /**
     * Debit wallet (used by TransactionService for transfers)
     *
     * @param id     The wallet ID
     * @param amount The amount to debit
     * @return WalletResponseDTO with updated wallet details
     */
    @Transactional
    public WalletResponseDTO debit(Long id, BigDecimal amount) {
        log.info("Debiting {} from wallet id: {}", amount, id);

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Amount must be positive");
        }

        Wallet wallet = walletRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found with id: " + id));

        if (!wallet.getIsActive()) {
            throw new BusinessException("Cannot debit from inactive wallet");
        }

        wallet.debit(amount); // Throws exception if insufficient balance
        Wallet savedWallet = walletRepository.save(wallet);
        log.info("Debited {} from wallet id: {}. New balance: {}", amount, id, savedWallet.getBalance());

        return mapToResponseDTO(savedWallet);
    }

    /**
     * Credit wallet (used by TransactionService for transfers)
     *
     * @param id     The wallet ID
     * @param amount The amount to credit
     * @return WalletResponseDTO with updated wallet details
     */
    @Transactional
    public WalletResponseDTO credit(Long id, BigDecimal amount) {
        log.info("Crediting {} to wallet id: {}", amount, id);

        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("Amount must be positive");
        }

        Wallet wallet = walletRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found with id: " + id));

        if (!wallet.getIsActive()) {
            throw new BusinessException("Cannot credit to inactive wallet");
        }

        wallet.credit(amount);
        Wallet savedWallet = walletRepository.save(wallet);
        log.info("Credited {} to wallet id: {}. New balance: {}", amount, id, savedWallet.getBalance());

        return mapToResponseDTO(savedWallet);
    }

    /**
     * Check if wallet has sufficient balance
     * 
     * @param id     The wallet ID
     * @param amount The amount to check
     * @return true if wallet has sufficient balance
     */
    public boolean hasSufficientBalance(Long id, BigDecimal amount) {
        Wallet wallet = walletRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Wallet not found with id: " + id));
        return wallet.hasSufficientBalance(amount);
    }

    /**
     * Check if wallet exists
     * 
     * @param id The wallet ID
     * @return true if wallet exists
     */
    public boolean existsById(Long id) {
        return walletRepository.existsById(id);
    }

    /**
     * Helper method to map Wallet entity to WalletResponseDTO
     * 
     * @param wallet The wallet entity
     * @return WalletResponseDTO
     */
    private WalletResponseDTO mapToResponseDTO(Wallet wallet) {
        return new WalletResponseDTO(
                wallet.getId(),
                wallet.getUserId(),
                wallet.getIsActive(),
                wallet.getBalance());
    }
}
