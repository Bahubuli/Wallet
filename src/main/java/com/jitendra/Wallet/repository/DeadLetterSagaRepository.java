package com.jitendra.Wallet.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.jitendra.Wallet.entity.DeadLetterSaga;

@Repository
public interface DeadLetterSagaRepository extends JpaRepository<DeadLetterSaga, Long> {

    List<DeadLetterSaga> findBySagaInstanceId(Long sagaInstanceId);

    List<DeadLetterSaga> findBySagaType(String sagaType);
}
