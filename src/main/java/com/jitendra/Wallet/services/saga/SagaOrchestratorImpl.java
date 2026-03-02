package com.jitendra.Wallet.services.saga;

import java.util.List;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitendra.Wallet.entity.DeadLetterSaga;
import com.jitendra.Wallet.entity.SagaInstance;
import com.jitendra.Wallet.entity.SagaStatus;
import com.jitendra.Wallet.entity.SagaStep;
import com.jitendra.Wallet.entity.StepStatus;
import com.jitendra.Wallet.repository.SagaInstanceRepository;
import com.jitendra.Wallet.repository.SagaStepRepository;
import com.jitendra.Wallet.repository.DeadLetterSagaRepository;
import com.jitendra.Wallet.services.saga.steps.SagaStepFactory;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Slf4j
public class SagaOrchestratorImpl implements SagaOrchestrator {

    private final ObjectMapper objectMapper;
    private final SagaInstanceRepository sagaInstanceRepository;
    private final SagaStepFactory sagaStepFactory;
    private final SagaStepRepository sagaStepRepository;
    private final DeadLetterSagaRepository deadLetterSagaRepository;

    @Override
    @Transactional
    public Long startSaga(SagaContext context) {
        try {
            String contextJson = objectMapper.writeValueAsString(context);

            SagaInstance sagaInstance = SagaInstance
                    .builder()
                    .sagaType(context.getSagaType())
                    .context(contextJson)
                    .status(SagaStatus.STARTED)
                    .build();

            sagaInstance = sagaInstanceRepository.save(sagaInstance);

            log.info("Saga started with id: {}", sagaInstance.getId());
            return sagaInstance.getId();
        } catch (Exception e) {
            log.error("Failed to start saga: {}", e.getMessage());
            throw new RuntimeException("Failed to start saga", e);
        }
    }

    /**
     * Execute a saga step WITHOUT an outer @Transactional so that the step's
     * own REQUIRES_NEW transaction can commit independently. State tracking
     * (status updates, context saves) each happen in their own REQUIRES_NEW
     * transaction via helper methods, so a crash mid-step never leaves the
     * orchestrator's bookkeeping in a half-committed state.
     */
    @Override
    public boolean executeStep(Long sagaInstanceId, String stepName, Integer stepOrder) {

        SagaInstance sagaInstance = sagaInstanceRepository.findById(sagaInstanceId)
                .orElseThrow(() -> new RuntimeException("SagaInstance not found with id: " + sagaInstanceId));

        SagaStepInterface step = sagaStepFactory.getSagaStepByName(stepName);
        if (step == null) {
            log.error("Saga step not found for step name: {}", stepName);
            throw new RuntimeException("Saga step not found for step name: " + stepName);
        }

        // Ensure the SagaStep entity exists in the DB (create if new)
        SagaStep sagaStep = initializeStepRecord(sagaInstanceId, sagaInstance, stepName, stepOrder);

        // Build a RetryTemplate that retries only transient failures
        int maxRetries = step.getMaxRetries() != null ? step.getMaxRetries() : 3;
        RetryTemplate retryTemplate = RetryTemplate.builder()
                .maxAttempts(maxRetries)
                .exponentialBackoff(500, 2.0, 5000)
                .retryOn(ObjectOptimisticLockingFailureException.class)
                .retryOn(CannotAcquireLockException.class)
                .retryOn(TransientDataAccessException.class)
                .build();

        try {
            // Deserialize context from the saga instance
            SagaContext context = objectMapper.readValue(sagaInstance.getContext(), SagaContext.class);

            // Mark step as RUNNING in its own transaction
            updateStepStatus(sagaStep.getId(), StepStatus.RUNNING, null);

            // Execute the business logic with retry support
            boolean result = retryTemplate.execute(retryContext -> {
                if (retryContext.getRetryCount() > 0) {
                    log.warn("Retrying saga step {} for sagaInstanceId {}, attempt {}/{}",
                            stepName, sagaInstanceId, retryContext.getRetryCount() + 1, maxRetries);
                    incrementStepRetryCount(sagaStep.getId());
                }
                return step.execute(context);
            });

            if (result) {
                // Mark step COMPLETED and persist updated context atomically in one transaction
                markStepCompletedAndSaveContext(sagaStep.getId(), sagaInstanceId, context);
                log.info("Saga step {} completed for sagaInstanceId {}", stepName, sagaInstanceId);
                return true;
            } else {
                updateStepStatus(sagaStep.getId(), StepStatus.FAILED, "Step returned false");
                log.error("Saga step {} failed for sagaInstanceId {}", stepName, sagaInstanceId);
                return false;
            }

        } catch (Exception e) {
            updateStepStatus(sagaStep.getId(), StepStatus.FAILED, e.getMessage());
            log.error("Saga step {} failed for sagaInstanceId {} after {} retries with error: {}",
                    stepName, sagaInstanceId, maxRetries, e.getMessage());
            return false;
        }

    }

    /**
     * Increment the retry count on a saga step in its own transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementStepRetryCount(Long sagaStepId) {
        SagaStep sagaStep = sagaStepRepository.findById(sagaStepId)
                .orElseThrow(() -> new RuntimeException("SagaStep not found with id: " + sagaStepId));
        sagaStep.setRetryCount(sagaStep.getRetryCount() + 1);
        sagaStepRepository.save(sagaStep);
    }

    /**
     * Initialize or fetch an existing SagaStep record in its own transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SagaStep initializeStepRecord(Long sagaInstanceId, SagaInstance sagaInstance, String stepName, Integer stepOrder) {
        SagaStep sagaStep = sagaStepRepository
                .findBySagaInstanceIdAndStatusAndStepName(sagaInstanceId, StepStatus.PENDING, stepName)
                .orElse(
                        SagaStep.builder()
                                .sagaInstance(sagaInstance)
                                .stepName(stepName)
                                .stepOrder(stepOrder)
                                .status(StepStatus.PENDING).build());

        if (sagaStep.getId() == null) {
            sagaStep = sagaStepRepository.save(sagaStep);
        }
        return sagaStep;
    }

    /**
     * Update a step's status (and optional error message) in its own transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateStepStatus(Long sagaStepId, StepStatus status, String errorMessage) {
        SagaStep sagaStep = sagaStepRepository.findById(sagaStepId)
                .orElseThrow(() -> new RuntimeException("SagaStep not found with id: " + sagaStepId));
        sagaStep.setStatus(status);
        if (errorMessage != null) {
            sagaStep.setErrorMessage(errorMessage);
        }
        sagaStepRepository.save(sagaStep);
    }

    /**
     * Mark step as COMPLETED and persist updated saga context atomically.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStepCompletedAndSaveContext(Long sagaStepId, Long sagaInstanceId, SagaContext context) {
        try {
            SagaStep sagaStep = sagaStepRepository.findById(sagaStepId)
                    .orElseThrow(() -> new RuntimeException("SagaStep not found with id: " + sagaStepId));
            sagaStep.setStatus(StepStatus.COMPLETED);
            sagaStepRepository.save(sagaStep);

            SagaInstance sagaInstance = sagaInstanceRepository.findById(sagaInstanceId)
                    .orElseThrow(() -> new RuntimeException("SagaInstance not found with id: " + sagaInstanceId));
            sagaInstance.setContext(objectMapper.writeValueAsString(context));
            sagaInstanceRepository.save(sagaInstance);
        } catch (Exception e) {
            throw new RuntimeException("Failed to mark step completed and save context", e);
        }
    }

    /**
     * Compensate a step WITHOUT an outer @Transactional — same reasoning as executeStep.
     */
    @Override
    public boolean compensateStep(Long sagaInstanceId, String stepName) {

        SagaInstance sagaInstance = sagaInstanceRepository.findById(sagaInstanceId)
                .orElseThrow(() -> new RuntimeException("SagaInstance not found with id: " + sagaInstanceId));

        SagaStepInterface step = sagaStepFactory.getSagaStepByName(stepName);
        if (step == null) {
            log.error("Saga step not found for step name: {}", stepName);
            throw new RuntimeException("Saga step not found for step name: " + stepName);
        }

        // fetch the completed saga step from database to compensate it
        SagaStep sagaStep = sagaStepRepository
                .findBySagaInstanceIdAndStatusAndStepName(sagaInstanceId, StepStatus.COMPLETED, stepName)
                .orElseThrow(() -> new RuntimeException("Completed saga step not found for step name: " + stepName));

        try {
            SagaContext context = objectMapper.readValue(sagaInstance.getContext(), SagaContext.class);

            // Mark step as RUNNING in its own transaction
            updateStepStatus(sagaStep.getId(), StepStatus.RUNNING, null);

            // Execute compensation (runs in REQUIRES_NEW inside the step itself)
            boolean result = step.compensate(context);

            if (result) {
                // Mark step COMPENSATED and persist updated context atomically
                markStepCompensatedAndSaveContext(sagaStep.getId(), sagaInstanceId, context);
                log.info("Saga step {} compensated for sagaInstanceId {}", stepName, sagaInstanceId);
                return true;
            } else {
                updateStepStatus(sagaStep.getId(), StepStatus.FAILED, "Compensation returned false");
                log.error("Saga step {} compensation failed for sagaInstanceId {}", stepName, sagaInstanceId);
                return false;
            }
        } catch (Exception e) {
            updateStepStatus(sagaStep.getId(), StepStatus.FAILED, "Compensation error: " + e.getMessage());
            log.error("Saga step {} compensation failed for sagaInstanceId {} with error: {}", stepName, sagaInstanceId,
                    e.getMessage());
            return false;
        }
    }

    /**
     * Mark step as COMPENSATED and persist updated saga context atomically.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markStepCompensatedAndSaveContext(Long sagaStepId, Long sagaInstanceId, SagaContext context) {
        try {
            SagaStep sagaStep = sagaStepRepository.findById(sagaStepId)
                    .orElseThrow(() -> new RuntimeException("SagaStep not found with id: " + sagaStepId));
            sagaStep.setStatus(StepStatus.COMPENSATED);
            sagaStepRepository.save(sagaStep);

            SagaInstance sagaInstance = sagaInstanceRepository.findById(sagaInstanceId)
                    .orElseThrow(() -> new RuntimeException("SagaInstance not found with id: " + sagaInstanceId));
            sagaInstance.setContext(objectMapper.writeValueAsString(context));
            sagaInstanceRepository.save(sagaInstance);
        } catch (Exception e) {
            throw new RuntimeException("Failed to mark step compensated and save context", e);
        }
    }

    @Override
    public SagaInstance getSagaInstance(Long sagaInstanceId) {
        return sagaInstanceRepository.findById(sagaInstanceId)
                .orElseThrow(() -> new RuntimeException("SagaInstance not found with id: " + sagaInstanceId));
    }

    /**
     * Update saga instance status in its own transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateSagaStatus(Long sagaInstanceId, SagaStatus status) {
        SagaInstance sagaInstance = sagaInstanceRepository.findById(sagaInstanceId)
                .orElseThrow(() -> new RuntimeException("SagaInstance not found with id: " + sagaInstanceId));
        sagaInstance.setStatus(status);
        sagaInstanceRepository.save(sagaInstance);
    }

    @Override
    public void compensateSaga(Long sagaInstanceId) {
        try {
            // Validate saga exists
            sagaInstanceRepository.findById(sagaInstanceId)
                    .orElseThrow(() -> new RuntimeException("SagaInstance not found with id: " + sagaInstanceId));

            // Find all completed steps in reverse order (highest stepOrder first) for compensation
            List<SagaStep> completedSteps = sagaStepRepository
                    .findBySagaInstanceIdAndStatusOrderByStepOrderDesc(sagaInstanceId, StepStatus.COMPLETED);

            if (completedSteps.isEmpty()) {
                log.info("No completed steps found for saga compensation, sagaInstanceId: {}", sagaInstanceId);
                updateSagaStatus(sagaInstanceId, SagaStatus.COMPENSATED);
                return;
            }

            log.info("Starting saga compensation for sagaInstanceId: {} with {} completed steps", sagaInstanceId,
                    completedSteps.size());

            // Update saga status to compensating
            updateSagaStatus(sagaInstanceId, SagaStatus.COMPENSATING);

            boolean allStepsCompensated = true;

            for (SagaStep step : completedSteps) {
                try {
                    boolean compensated = compensateStep(sagaInstanceId, step.getStepName());
                    if (!compensated) {
                        allStepsCompensated = false;
                        log.error("Failed to compensate step: {} for sagaInstanceId: {}", step.getStepName(),
                                sagaInstanceId);
                        break;
                    }
                } catch (Exception e) {
                    allStepsCompensated = false;
                    log.error("Exception occurred while compensating step: {} for sagaInstanceId: {}, error: {}",
                            step.getStepName(), sagaInstanceId, e.getMessage());
                    break;
                }
            }

            // Update final saga status
            if (allStepsCompensated) {
                updateSagaStatus(sagaInstanceId, SagaStatus.COMPENSATED);
                log.info("Saga compensation completed successfully for sagaInstanceId: {}", sagaInstanceId);
            } else {
                failSaga(sagaInstanceId);
                log.error("Saga compensation failed for sagaInstanceId: {}", sagaInstanceId);
            }

        } catch (Exception e) {
            log.error("Failed to compensate saga with id: {}, error: {}", sagaInstanceId, e.getMessage());
            failSaga(sagaInstanceId);
            throw new RuntimeException("Failed to compensate saga", e);
        }
    }

    @Override
    public void failSaga(Long sagaInstanceId) {
        try {
            updateSagaStatus(sagaInstanceId, SagaStatus.FAILED);
            sendToDeadLetterQueue(sagaInstanceId);
            log.error("Saga marked as failed and sent to DLQ for sagaInstanceId: {}", sagaInstanceId);
        } catch (Exception e) {
            log.error("Failed to mark saga as failed for id: {}, error: {}", sagaInstanceId, e.getMessage());
            throw new RuntimeException("Failed to mark saga as failed", e);
        }
    }

    /**
     * Send a permanently failed saga to the dead-letter queue for manual review.
     */
    private void sendToDeadLetterQueue(Long sagaInstanceId) {
        try {
            SagaInstance saga = sagaInstanceRepository.findById(sagaInstanceId)
                    .orElseThrow(() -> new RuntimeException("SagaInstance not found: " + sagaInstanceId));

            DeadLetterSaga dlq = DeadLetterSaga.builder()
                    .sagaInstanceId(sagaInstanceId)
                    .sagaType(saga.getSagaType())
                    .lastStatus(saga.getStatus().name())
                    .contextSnapshot(saga.getContext())
                    .errorDetails(saga.getErrorDetails())
                    .build();

            deadLetterSagaRepository.save(dlq);
            log.info("Saga id: {} sent to dead-letter queue", sagaInstanceId);
        } catch (Exception e) {
            // DLQ write failure should not prevent the saga from being marked as failed
            log.error("Failed to send saga id: {} to dead-letter queue: {}", sagaInstanceId, e.getMessage());
        }
    }

    @Override
    public void completeSaga(Long sagaInstanceId) {
        try {
            updateSagaStatus(sagaInstanceId, SagaStatus.COMPLETED);
            log.info("Saga completed successfully for sagaInstanceId: {}", sagaInstanceId);
        } catch (Exception e) {
            log.error("Failed to mark saga as completed for id: {}, error: {}", sagaInstanceId, e.getMessage());
            throw new RuntimeException("Failed to mark saga as completed", e);
        }
    }

}
