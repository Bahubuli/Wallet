package com.jitendra.Wallet.repository;

import com.jitendra.Wallet.entity.Wallet;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, Long> {
    
    List<Wallet> findByUserId(Long userId);

    // Acquires a database-level pessimistic write lock:
    // Prevents other transactions from reading or writing
    // the locked row until the current transaction completes.
    // Ensures data consistency in concurrent scenarios,
    //  such as wallet balance updates.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    // Custom JPQL query to fetch Wallet by id
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    // Binds method parameter 'id' to query parameter ':id'
    Optional<Wallet> findByIdWithLock(@Param("id") Long id);
    
}
