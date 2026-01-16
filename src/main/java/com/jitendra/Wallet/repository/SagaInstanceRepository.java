package com.jitendra.Wallet.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.jitendra.Wallet.entity.SagaInstance;
import com.jitendra.Wallet.entity.SagaStatus;

@Repository
public interface SagaInstanceRepository extends JpaRepository<SagaInstance, Long> {
    
    // Find saga instances by saga type
    List<SagaInstance> findBySagaType(String sagaType);
    
    // Find saga instances by status
    List<SagaInstance> findByStatus(SagaStatus status);
    
    // Find saga instances by saga type and status
    List<SagaInstance> findBySagaTypeAndStatus(String sagaType, SagaStatus status);
    
    // Find saga instances that are stuck/zombie (expired timeout)
    @Query("SELECT s FROM SagaInstance s WHERE s.expiryTime IS NOT NULL AND s.expiryTime <= :currentTime AND s.status IN ('STARTED', 'RUNNING', 'COMPENSATING')")
    List<SagaInstance> findExpiredSagaInstances(@Param("currentTime") LocalDateTime currentTime);
    
    // Find saga instances that failed and need recovery
    @Query("SELECT s FROM SagaInstance s WHERE s.status = 'FAILED' AND s.retryCount < s.maxRetries")
    List<SagaInstance> findFailedSagasEligibleForRetry();
    
    // Find saga instances by current step name
    List<SagaInstance> findByCurrentStep(String currentStep);
    
    // Find saga instances created after a specific date for audit/analytics
    List<SagaInstance> findByCreatedDateAfter(Instant createdDate);
    
    // Find saga instances by status and created date (for filtering completed/failed sagas)
    List<SagaInstance> findByStatusAndCreatedDateAfter(SagaStatus status, Instant createdDate);
    
    // Find saga instances that completed within a time range
    @Query("SELECT s FROM SagaInstance s WHERE s.status = 'COMPLETED' AND s.completedDate BETWEEN :startDate AND :endDate")
    List<SagaInstance> findCompletedSagasBetweenDates(@Param("startDate") Instant startDate, @Param("endDate") Instant endDate);
    
    // Count saga instances by status (for metrics/monitoring)
    long countByStatus(SagaStatus status);
    
    // Count saga instances by saga type and status
    long countBySagaTypeAndStatus(String sagaType, SagaStatus status);
}
