package com.jitendra.Wallet.services.saga;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.RecoveryCallback;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitendra.Wallet.entity.SagaInstance;
import com.jitendra.Wallet.entity.SagaStatus;
import com.jitendra.Wallet.entity.SagaStep;
import com.jitendra.Wallet.entity.StepStatus;
import com.jitendra.Wallet.repository.SagaInstanceRepository;
import com.jitendra.Wallet.repository.SagaStepRepository;
import com.jitendra.Wallet.services.saga.steps.SagaStepFactory;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Slf4j
public class SagaOrchestratorImpl implements SagaOrchestrator {
    // Implementation details...

    private final ObjectMapper objectMapper;
    private final SagaInstanceRepository sagaInstanceRepository;
    private final SagaStepFactory sagaStepFactory;
    private final SagaStepRepository sagaStepRepository;

    /**
     * Exception types considered transient (temporary) — safe to retry.
     * Permanent exceptions (e.g. EntityNotFoundException) are NOT listed here
     * and will bypass the retry loop immediately.
     */
    private static final Map<Class<? extends Throwable>, Boolean> TRANSIENT_EXCEPTIONS;
    static {
        TRANSIENT_EXCEPTIONS = new HashMap<>();
        TRANSIENT_EXCEPTIONS.put(ObjectOptimisticLockingFailureException.class, true);
        TRANSIENT_EXCEPTIONS.put(CannotAcquireLockException.class, true);
        TRANSIENT_EXCEPTIONS.put(TransientDataAccessException.class, true);
    }

    /**
     * Builds a RetryTemplate that retries only on transient exceptions with
     * exponential back-off: 1 s → 2 s → 4 s … capped at 10 s.
     *
     * @param maxAttempts total attempts (first try + retries), taken from sagaStep.maxRetries
     */
    private RetryTemplate buildRetryTemplate(int maxAttempts) {
        // traverseCauses=true → also matches subclasses of the listed exceptions
        SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(maxAttempts, TRANSIENT_EXCEPTIONS, true);

        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(1_000L);  // 1 second
        backOffPolicy.setMultiplier(2.0);           // doubles each time: 1s, 2s, 4s …
        backOffPolicy.setMaxInterval(10_000L);      // cap at 10 seconds

        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(retryPolicy);
        retryTemplate.setBackOffPolicy(backOffPolicy);
        return retryTemplate;
    }

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

    @Override
    @Transactional(noRollbackFor = {
            TransientDataAccessException.class,
            ObjectOptimisticLockingFailureException.class,
            CannotAcquireLockException.class
    })
    public boolean executeStep(Long sagaInstanceId, String stepName, Integer stepOrder) {

        SagaInstance sagaInstance = sagaInstanceRepository.findById(sagaInstanceId)
                .orElseThrow(() -> new RuntimeException("SagaInstance not found with id: " + sagaInstanceId));

        SagaStepInterface step = sagaStepFactory.getSagaStepByName(stepName);
        if (step == null) {
            log.error("Saga step not found for step name: {}", stepName);
            throw new RuntimeException("Saga step not found for step name: " + stepName);
        }

        // Fetch existing PENDING step from DB, or build a new one
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

        try {
            SagaContext context = objectMapper.readValue(sagaInstance.getContext(), SagaContext.class);
            sagaStep.setStatus(StepStatus.RUNNING);
            
            //The final keyword here is required by Java because runningSagaStep is referenced inside the
            //  lambda below. Any variable used inside a lambda must be effectively final.
            final SagaStep runningSagaStep = sagaStepRepository.save(sagaStep);

            // Build a RetryTemplate driven by the step's own maxRetries setting.
            // Only transient exceptions (lock contention, optimistic locking, etc.)
            // are retried; permanent errors propagate immediately.
            RetryTemplate retryTemplate = buildRetryTemplate(runningSagaStep.getMaxRetries());

            boolean result = retryTemplate.execute(
                    (RetryCallback<Boolean, Exception>) retryContext -> {
                        // retryContext.getRetryCount() == 0 on the very first attempt
                        if (retryContext.getRetryCount() > 0) {
                            runningSagaStep.setRetryCount(runningSagaStep.getRetryCount() + 1);
                            sagaStepRepository.save(runningSagaStep);
                            log.warn("Retrying saga step '{}' for sagaInstanceId {}, attempt {}/{}, "
                                    + "previous error: {}",
                                    stepName, sagaInstanceId,
                                    retryContext.getRetryCount() + 1,
                                    runningSagaStep.getMaxRetries(),
                                    retryContext.getLastThrowable() != null
                                            ? retryContext.getLastThrowable().getMessage() : "unknown");
                        }
                        return step.execute(context);
                    },
                    (RecoveryCallback<Boolean>) recoveryContext -> {
                        // Reached only when every attempt threw a transient exception
                        Throwable lastError = recoveryContext.getLastThrowable();
                        log.error("All {} attempts exhausted for saga step '{}' in sagaInstanceId {}. "
                                + "Final error: {}",
                                runningSagaStep.getMaxRetries(), stepName, sagaInstanceId,
                                lastError != null ? lastError.getMessage() : "unknown");
                        if (lastError != null) {
                            runningSagaStep.setErrorMessage(lastError.getMessage());
                        }
                        return false;
                    }
            );

            if (result) {
                runningSagaStep.setStatus(StepStatus.COMPLETED);
                sagaStepRepository.save(runningSagaStep);

                // Serialize and persist any data added to context during step execution
                sagaInstance.setContext(objectMapper.writeValueAsString(context));
                sagaInstanceRepository.save(sagaInstance);

                log.info("Saga step '{}' completed for sagaInstanceId {}", stepName, sagaInstanceId);
                return true;
            } else {
                runningSagaStep.setStatus(StepStatus.FAILED);
                sagaStepRepository.save(runningSagaStep);
                log.error("Saga step '{}' failed for sagaInstanceId {}", stepName, sagaInstanceId);
                return false;
            }

        } catch (Exception e) {
            // Permanent (non-transient) failure — no retry, mark step as FAILED immediately
            sagaStep.setStatus(StepStatus.FAILED);
            sagaStep.setErrorMessage(e.getMessage());
            sagaStepRepository.save(sagaStep);
            log.error("Saga step '{}' failed permanently for sagaInstanceId {} with error: {}",
                    stepName, sagaInstanceId, e.getMessage());
            return false;
        }
    }

    @Override
    @Transactional(noRollbackFor = {
            TransientDataAccessException.class,
            ObjectOptimisticLockingFailureException.class,
            CannotAcquireLockException.class
    })
    public boolean compensateStep(Long sagaInstanceId, String stepName) {

        SagaInstance sagaInstance = sagaInstanceRepository.findById(sagaInstanceId)
                .orElseThrow(() -> new RuntimeException("SagaInstance not found with id: " + sagaInstanceId));

        SagaStepInterface step = sagaStepFactory.getSagaStepByName(stepName);
        if (step == null) {
            log.error("Saga step not found for step name: {}", stepName);
            throw new RuntimeException("Saga step not found for step name: " + stepName);
        }

        // Fetch the completed step that needs to be rolled back
        SagaStep sagaStep = sagaStepRepository
                .findBySagaInstanceIdAndStatusAndStepName(sagaInstanceId, StepStatus.COMPLETED, stepName)
                .orElseThrow(() -> new RuntimeException("Completed saga step not found for step name: " + stepName));

        try {
            SagaContext context = objectMapper.readValue(sagaInstance.getContext(), SagaContext.class);
            sagaStep.setStatus(StepStatus.RUNNING);
            final SagaStep runningSagaStep = sagaStepRepository.save(sagaStep);

            RetryTemplate retryTemplate = buildRetryTemplate(runningSagaStep.getMaxRetries());

            boolean result = retryTemplate.execute(
                    (RetryCallback<Boolean, Exception>) retryContext -> {
                        if (retryContext.getRetryCount() > 0) {
                            runningSagaStep.setRetryCount(runningSagaStep.getRetryCount() + 1);
                            sagaStepRepository.save(runningSagaStep);
                            log.warn("Retrying compensation of saga step '{}' for sagaInstanceId {}, "
                                    + "attempt {}/{}, previous error: {}",
                                    stepName, sagaInstanceId,
                                    retryContext.getRetryCount() + 1,
                                    runningSagaStep.getMaxRetries(),
                                    retryContext.getLastThrowable() != null
                                            ? retryContext.getLastThrowable().getMessage() : "unknown");
                        }
                        return step.compensate(context);
                    },
                    (RecoveryCallback<Boolean>) recoveryContext -> {
                        Throwable lastError = recoveryContext.getLastThrowable();
                        log.error("All {} compensation attempts exhausted for saga step '{}' in sagaInstanceId {}. "
                                + "Final error: {}",
                                runningSagaStep.getMaxRetries(), stepName, sagaInstanceId,
                                lastError != null ? lastError.getMessage() : "unknown");
                        if (lastError != null) {
                            runningSagaStep.setErrorMessage(lastError.getMessage());
                        }
                        return false;
                    }
            );

            if (result) {
                runningSagaStep.setStatus(StepStatus.COMPENSATED);
                sagaStepRepository.save(runningSagaStep);

                // Serialize and persist any data added to context during compensation
                sagaInstance.setContext(objectMapper.writeValueAsString(context));
                sagaInstanceRepository.save(sagaInstance);

                log.info("Saga step '{}' compensated for sagaInstanceId {}", stepName, sagaInstanceId);
                return true;
            } else {
                runningSagaStep.setStatus(StepStatus.FAILED);
                sagaStepRepository.save(runningSagaStep);
                log.error("Saga step '{}' compensation failed for sagaInstanceId {}", stepName, sagaInstanceId);
                return false;
            }
        } catch (Exception e) {
            sagaStep.setStatus(StepStatus.FAILED);
            sagaStep.setErrorMessage(e.getMessage());
            sagaStepRepository.save(sagaStep);
            log.error("Saga step '{}' compensation failed permanently for sagaInstanceId {} with error: {}",
                    stepName, sagaInstanceId, e.getMessage());
            return false;
        }
    }

    @Override
    public SagaInstance getSagaInstance(Long sagaInstanceId) {
        return sagaInstanceRepository.findById(sagaInstanceId)
                .orElseThrow(() -> new RuntimeException("SagaInstance not found with id: " + sagaInstanceId));
    }

    @Override
    @Transactional
    public void compensateSaga(Long sagaInstanceId) {
        try {
            SagaInstance sagaInstance = sagaInstanceRepository.findById(sagaInstanceId)
                    .orElseThrow(() -> new RuntimeException("SagaInstance not found with id: " + sagaInstanceId));

            // Find all completed steps for this saga instance
            List<SagaStep> completedSteps = sagaStepRepository.findBySagaInstanceIdAndStatus(sagaInstanceId,
                    StepStatus.COMPLETED);

            if (completedSteps.isEmpty()) {
                log.info("No completed steps found for saga compensation, sagaInstanceId: {}", sagaInstanceId);
                sagaInstance.setStatus(SagaStatus.COMPENSATED);
                sagaInstanceRepository.save(sagaInstance);
                return;
            }

            log.info("Starting saga compensation for sagaInstanceId: {} with {} completed steps", sagaInstanceId,
                    completedSteps.size());

            // Update saga status to compensating
            sagaInstance.setStatus(SagaStatus.COMPENSATING);
            sagaInstanceRepository.save(sagaInstance);

            // Compensate steps in reverse order (LIFO)
            Collections.reverse(completedSteps);

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
            //  re-read saga instance to get latest version after
            // compensation steps modified it, preventing ObjectOptimisticLockingFailureException
            if (allStepsCompensated) {
                SagaInstance refreshedSagaInstance = sagaInstanceRepository.findById(sagaInstanceId)
                        .orElseThrow(() -> new RuntimeException("SagaInstance not found with id: " + sagaInstanceId));
                refreshedSagaInstance.setStatus(SagaStatus.COMPENSATED);
                sagaInstanceRepository.save(refreshedSagaInstance);
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
    @Transactional
    public void failSaga(Long sagaInstanceId) {
        try {
            SagaInstance sagaInstance = sagaInstanceRepository.findById(sagaInstanceId)
                    .orElseThrow(() -> new RuntimeException("SagaInstance not found with id: " + sagaInstanceId));

            sagaInstance.setStatus(SagaStatus.FAILED);
            sagaInstanceRepository.save(sagaInstance);
            log.error("Saga marked as failed for sagaInstanceId: {}", sagaInstanceId);
        } catch (Exception e) {
            log.error("Failed to mark saga as failed for id: {}, error: {}", sagaInstanceId, e.getMessage());
            throw new RuntimeException("Failed to mark saga as failed", e);
        }
    }

    @Override
    @Transactional
    public void completeSaga(Long sagaInstanceId) {
        try {
            SagaInstance sagaInstance = sagaInstanceRepository.findById(sagaInstanceId)
                    .orElseThrow(() -> new RuntimeException("SagaInstance not found with id: " + sagaInstanceId));

            sagaInstance.setStatus(SagaStatus.COMPLETED);
            sagaInstanceRepository.save(sagaInstance);
            log.info("Saga completed successfully for sagaInstanceId: {}", sagaInstanceId);
        } catch (Exception e) {
            log.error("Failed to mark saga as completed for id: {}, error: {}", sagaInstanceId, e.getMessage());
            throw new RuntimeException("Failed to mark saga as completed", e);
        }
    }

}
