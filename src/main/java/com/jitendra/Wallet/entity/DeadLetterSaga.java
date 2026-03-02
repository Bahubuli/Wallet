package com.jitendra.Wallet.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Captures permanently failed sagas for manual review and intervention.
 * When a saga fails all retries AND compensation also fails, it lands here.
 */
@Entity
@Table(name = "dead_letter_saga")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeadLetterSaga {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "saga_instance_id", nullable = false)
    private Long sagaInstanceId;

    @Column(name = "saga_type", nullable = false)
    private String sagaType;

    @Column(name = "last_status", nullable = false)
    private String lastStatus;

    @Lob
    @Column(name = "context_snapshot")
    private String contextSnapshot;

    @Lob
    @Column(name = "error_details")
    private String errorDetails;

    @Column(name = "created_date", nullable = false, updatable = false)
    private LocalDateTime createdDate;

    @PrePersist
    protected void onCreate() {
        createdDate = LocalDateTime.now();
    }
}
