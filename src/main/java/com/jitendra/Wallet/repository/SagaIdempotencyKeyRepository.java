package com.jitendra.Wallet.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.jitendra.Wallet.entity.SagaIdempotencyKey;

@Repository
public interface SagaIdempotencyKeyRepository extends JpaRepository<SagaIdempotencyKey, Long> {

    boolean existsByIdempotencyKey(String idempotencyKey);

    Optional<SagaIdempotencyKey> findByIdempotencyKey(String idempotencyKey);
}
