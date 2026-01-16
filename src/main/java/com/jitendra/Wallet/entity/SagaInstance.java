package com.jitendra.Wallet.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.OneToMany;
import jakarta.persistence.CascadeType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import java.time.Instant;
import java.time.LocalDateTime;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Column;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.util.List;

@Entity
@Table(name = "saga_instance", indexes = {
    @Index(name = "idx_saga_status_created", columnList = "status, created_date"),
    @Index(name = "idx_saga_type_status", columnList = "saga_type, status")
})
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SagaInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Identifies which saga workflow this instance belongs to (e.g., "USER_REGISTRATION", "PAYMENT_TRANSFER")
    @Column(name = "saga_type", nullable = false, length = 100)
    private String sagaType;

    // Current status of the saga orchestration
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private SagaStatus status = SagaStatus.STARTED;

    // JSON context data containing saga input parameters and intermediate results
    @Column(name="context", nullable = false, columnDefinition = "jsonb")
    private String context;

    // Name of the current step being executed
    @Column(name = "current_step", nullable = false)
    private String currentStep;

    // Track when saga completed or failed - important for audit and metrics
    @Column(name = "completed_date")
    private Instant completedDate;

    // Track when compensation process completed (for rollback scenarios)
    @Column(name = "compensated_date")
    private LocalDateTime compensatedDate;

    // Store error information at saga level for debugging and alerting
    @Column(name = "error_details", columnDefinition = "TEXT")
    private String errorDetails;

    // Track how many times saga has been retried (for failure analysis)
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    // Configurable retry limit per saga instance (prevent infinite loops)
    @Column(name = "max_retries", nullable = false)
    private Integer maxRetries = 3;

    // Timeout threshold to handle stuck/zombie sagas (in minutes)
    @Column(name = "timeout_minutes", nullable = false)
    private Integer timeoutMinutes = 60;

    // Expiry time calculated based on timeout - used by cleanup jobs
    @Column(name = "expiry_time")
    private LocalDateTime expiryTime;

    // Optimistic locking for concurrent updates - prevents lost updates
    @Version
    @Column(name = "version")
    private Long version;

    // Audit field - automatically populated on creation
    @CreatedDate
    @Column(name = "created_date", nullable = false, updatable = false)
    private Instant createdDate;

    // Audit field - automatically updated on every modification
    @LastModifiedDate
    @Column(name = "updated_date", nullable = false)
    private Instant updatedDate;

    // Bidirectional relationship - saga instance contains multiple steps
    @OneToMany(mappedBy = "sagaInstance", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<SagaStep> steps;

}
