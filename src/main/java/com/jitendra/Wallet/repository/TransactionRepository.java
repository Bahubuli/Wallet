package com.jitendra.Wallet.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.jitendra.Wallet.entity.Transaction;
import com.jitendra.Wallet.entity.TransactionStatus;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    
    // Find transactions by source wallet
    List<Transaction> findBySourceWalletId(Long sourceWalletId);
    
    // Find transactions by destination wallet
    List<Transaction> findByDestinationWalletId(Long destinationWalletId);
    
    // Find transactions by saga instance
    List<Transaction> findBySagaInstanceId(Long sagaInstanceId);
    
    // Find transactions by status
    List<Transaction> findByStatus(String status);
    
    // Find transactions by type
    List<Transaction> findByType(String type);
    
    // Find transactions by source wallet and status
    List<Transaction> findBySourceWalletIdAndStatus(Long sourceWalletId, String status);
    
    // Find transactions by destination wallet and status
    List<Transaction> findByDestinationWalletIdAndStatus(Long destinationWalletId, String status);
    
    // Find transactions by source wallet and type
    List<Transaction> findBySourceWalletIdAndType(Long sourceWalletId, String type);
    
    // Find transactions by destination wallet and type
    List<Transaction> findByDestinationWalletIdAndType(Long destinationWalletId, String type);
    
    // Find transactions by saga instance and status
    List<Transaction> findBySagaInstanceIdAndStatus(Long sagaInstanceId, String status);
    
    // Find transactions by status and type
    List<Transaction> findByStatusAndType(String status, String type);
    
    // Find transactions by source and destination wallet
    List<Transaction> findBySourceWalletIdAndDestinationWalletId(Long sourceWalletId, Long destinationWalletId);
    
    // Find transactions where wallet ID is either source or destination
    @Query("SELECT t FROM Transaction t WHERE t.sourceWalletId = :walletId OR t.destinationWalletId = :walletId")
    List<Transaction> findByWalletId(@Param("walletId") Long walletId);
    
    // Find transactions by status using enum
    @Query("SELECT t FROM Transaction t WHERE t.status = :status")
    List<Transaction> findByStatus(@Param("status") TransactionStatus status);

}
