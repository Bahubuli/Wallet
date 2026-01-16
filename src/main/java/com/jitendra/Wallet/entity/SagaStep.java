package com.jitendra.Wallet.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import java.time.Instant;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "saga_step", 
    indexes = {
        @Index(name = "idx_step_saga_order", columnList = "saga_instance_id, step_order"),
        @Index(name = "idx_step_saga_status", columnList = "saga_instance_id, status")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_saga_step_order", columnNames = {"saga_instance_id", "step_order"})
    }
)
@EntityListeners(AuditingEntityListener.class)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SagaStep {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Many-to-one relationship with SagaInstance (proper foreign key)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "saga_instance_id", nullable = false, foreignKey = @jakarta.persistence.ForeignKey(name = "fk_saga_step_instance"))
    private SagaInstance sagaInstance;

    // Order of execution - critical for orchestration and determining next step
    @Column(name = "step_order", nullable = false)
    private Integer stepOrder;

    // Name of the step (e.g., "CREATE_USER", "ALLOCATE_WALLET", "SEND_NOTIFICATION")
    @Column(name = "step_name", nullable = false, length = 100)
    private String stepName;

    // Current status of this specific step
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private StepStatus status = StepStatus.PENDING;

    // Name of compensation method/action to execute if rollback is needed
    @Column(name = "compensation_action", length = 100)
    private String compensationAction;

    // Error message if step fails - for debugging and monitoring
    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    // Track step-level retries (some steps may need more retries than others)
    @Column(name = "retry_count", nullable = false)
    private Integer retryCount = 0;

    // Step-level retry configuration (allows per-step retry customization)
    @Column(name = "max_retries", nullable = false)
    private Integer maxRetries = 3;

    // JSON data containing step input parameters and output results
    @Column(name="step_data", columnDefinition = "jsonb")
    private String stepData;

    // When step was created/initialized - distinguishes from actually started
    @CreatedDate
    @Column(name = "created_date", nullable = false, updatable = false)
    private Instant createdDate;

    // When step actually started execution (different from creation time)
    @Column(name = "started_date")
    private Instant startedDate;

    // When step completed successfully or failed
    @Column(name = "completed_date")
    private Instant completedDate;

    // Optimistic locking for concurrent updates - prevents race conditions
    @Version
    @Column(name = "version")
    private Long version;

}
