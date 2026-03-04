package com.jitendra.Wallet.repository;

import com.jitendra.Wallet.entity.Wallet;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, Long> {

    /**
     * Non-paginated: kept for internal use (e.g. validation checks that just
     * need to know IF wallets exist for a user, not present them to an API caller).
     */
    List<Wallet> findByUserId(Long userId);

    /**
     * Paginated: used by GET /wallets/user/{userId}.
     * A user could have many wallets in a large system, so we paginate at the
     * DB level rather than loading all of them into memory.
     */
    Page<Wallet> findByUserId(Long userId, Pageable pageable);
}
