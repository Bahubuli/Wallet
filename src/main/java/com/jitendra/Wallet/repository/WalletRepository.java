package com.jitendra.Wallet.repository;

import com.jitendra.Wallet.entity.Wallet;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, Long> {
    
    List<Wallet> findByUserId(Long userId);
    
}
