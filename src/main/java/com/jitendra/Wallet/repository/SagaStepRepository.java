package com.jitendra.Wallet.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.jitendra.Wallet.entity.SagaStep;
import com.jitendra.Wallet.entity.StepStatus;


@Repository
public interface SagaStepRepository extends JpaRepository<SagaStep, Long> {
    
    List<SagaStep> findBySagaInstance_Id(Long sagaInstanceId);

    @Query("SELECT s FROM SagaStep s WHERE s.sagaInstance.id = :sagaInstanceId AND s.status = :status")
    List<SagaStep> findCompletedSagaStepsBySagaInstanceId(@Param("sagaInstanceId") Long sagaInstanceId, @Param("status") StepStatus status);
}
