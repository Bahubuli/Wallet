package com.jitendra.Wallet.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.jitendra.Wallet.entity.Transaction;
import com.jitendra.Wallet.entity.TransactionStatus;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    // -------------------------------------------------------------------------
    // NON-PAGINATED — kept for saga-internal use only.
    // The saga orchestrator looks up steps/transactions during compensation.
    // These lists are tiny (≤10 saga steps) and MUST NOT be paginated
    // because truncating them would break saga rollback logic.
    // -------------------------------------------------------------------------

    /** Used by saga internals to find all transactions for a given saga. */
    List<Transaction> findBySagaInstanceId(Long sagaInstanceId);

    /** Used by saga internals to find transactions in a specific state. */
    List<Transaction> findBySagaInstanceIdAndStatus(Long sagaInstanceId, String status);

    // -------------------------------------------------------------------------
    // PAGINATED — used by the public REST API layer.
    // These can return large unbounded result sets (e.g. thousands of
    // transactions for an active wallet) so pagination is essential.
    // Spring Data automatically generates:
    // SELECT ... WHERE ... LIMIT :size OFFSET :page*size
    // SELECT COUNT(*) WHERE ... (for totalElements)
    // -------------------------------------------------------------------------

    /** GET /transactions/source/{id} */
    Page<Transaction> findBySourceWalletId(Long sourceWalletId, Pageable pageable);

    /** GET /transactions/destination/{id} */
    Page<Transaction> findByDestinationWalletId(Long destinationWalletId, Pageable pageable);

    /**
     * GET /transactions/saga/{id} — public endpoint, different from internal
     * findBySagaInstanceId
     */
    Page<Transaction> findBySagaInstanceId(Long sagaInstanceId, Pageable pageable);

    /** GET /transactions/status?status=PENDING */
    Page<Transaction> findByStatus(String status, Pageable pageable);

    /** GET /transactions/between?sourceWalletId=1&destinationWalletId=2 */
    Page<Transaction> findBySourceWalletIdAndDestinationWalletId(
            Long sourceWalletId, Long destinationWalletId, Pageable pageable);

    /**
     * GET /transactions/wallet/{id}
     * Custom JPQL: Spring cannot derive "WHERE src=? OR dst=?" from a method name
     * alone, so we write it explicitly. Adding Pageable here is the same as
     * any other method — Spring wraps it in LIMIT/OFFSET automatically.
     */
    @Query("SELECT t FROM Transaction t WHERE t.sourceWalletId = :walletId OR t.destinationWalletId = :walletId")
    Page<Transaction> findByWalletId(@Param("walletId") Long walletId, Pageable pageable);

    /**
     * GET /transactions/wallet/{id}/successful and /failed
     * Compound filter: wallet (source OR destination) AND status.
     * Previously done in-memory with .filter() — that loaded ALL transactions
     * for the wallet before discarding most of them. This query lets the DB do
     * the filtering, and Pageable adds LIMIT/OFFSET on top of that.
     */
    @Query("SELECT t FROM Transaction t WHERE (t.sourceWalletId = :walletId OR t.destinationWalletId = :walletId) AND t.status = :status")
    Page<Transaction> findByWalletIdAndStatus(
            @Param("walletId") Long walletId,
            @Param("status") TransactionStatus status,
            Pageable pageable);

    // -------------------------------------------------------------------------
    // Remaining non-paginated methods (used by derived filters, kept for safety)
    // -------------------------------------------------------------------------

    List<Transaction> findBySourceWalletId(Long sourceWalletId);

    List<Transaction> findByDestinationWalletId(Long destinationWalletId);

    List<Transaction> findByStatus(String status);

    List<Transaction> findByType(String type);

    List<Transaction> findBySourceWalletIdAndStatus(Long sourceWalletId, String status);

    List<Transaction> findByDestinationWalletIdAndStatus(Long destinationWalletId, String status);

    List<Transaction> findBySourceWalletIdAndType(Long sourceWalletId, String type);

    List<Transaction> findByDestinationWalletIdAndType(Long destinationWalletId, String type);

    List<Transaction> findByStatusAndType(String status, String type);

    List<Transaction> findBySourceWalletIdAndDestinationWalletId(Long sourceWalletId, Long destinationWalletId);

    @Query("SELECT t FROM Transaction t WHERE t.sourceWalletId = :walletId OR t.destinationWalletId = :walletId")
    List<Transaction> findByWalletId(@Param("walletId") Long walletId);

    @Query("SELECT t FROM Transaction t WHERE t.status = :status")
    List<Transaction> findByStatus(@Param("status") TransactionStatus status);
}
