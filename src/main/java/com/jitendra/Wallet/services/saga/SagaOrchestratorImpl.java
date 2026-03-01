package com.jitendra.Wallet.services.saga;

import java.util.Collections;
import java.util.List;

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
    @Transactional
    public boolean executeStep(Long sagaInstanceId, String stepName, Integer stepOrder) {

        SagaInstance sagaInstance = sagaInstanceRepository.findById(sagaInstanceId)
                .orElseThrow(() -> new RuntimeException("SagaInstance not found with id: " + sagaInstanceId));

        // now we have sagaInstance object we also need saga step object in order to
        // execute the step
        // for that we need to create the object for saga step
        // now similar to saga instance we don't want one exact step class for all the
        // saga types
        // rather we want to have different step classes for different saga types
        // so for that we can create a factory class which will return the step object
        // based
        // on the saga type and step name
        // once we have the step object we can call the execute method on it to execute
        // the step
        // this is saga step object for methods

        // With @Autowired, you'd need to know at compile time which specific step class
        // you want,
        // but here the step name comes dynamically from the database or workflow
        // configuration.

        SagaStepInterface step = sagaStepFactory.getSagaStepByName(stepName);
        if (step == null) {
            log.error("Saga step not found for step name: {}", stepName);
            throw new RuntimeException("Saga step not found for step name: " + stepName);
        }
        // fetch the saga step from database, by sagaInstanceId and stepName
        // if saga step is not found then create a new saga step with status PENDING

        // this is saga step entity from database, hai to theek verna build kr do
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
            sagaStepRepository.save(sagaStep);

            boolean result = step.execute(context);

            if (result) {
                sagaStep.setStatus(StepStatus.COMPLETED);
                sagaStepRepository.save(sagaStep);
                log.info("Saga step {} completed for sagaInstanceId {}", stepName, sagaInstanceId);
                return true;
            } else {
                sagaStep.setStatus(StepStatus.FAILED);
                sagaStepRepository.save(sagaStep);
                log.error("Saga step {} failed for sagaInstanceId {}", stepName, sagaInstanceId);
                return false;
            }

        } catch (Exception e) {
            sagaStep.setStatus(StepStatus.FAILED);
            sagaStepRepository.save(sagaStep);
            log.error("Saga step {} failed for sagaInstanceId {} with error: {}", stepName, sagaInstanceId,
                    e.getMessage());
            return false;
        }

    }

    @Override
    @Transactional
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
            sagaStep.setStatus(StepStatus.RUNNING);
            sagaStepRepository.save(sagaStep);

            boolean result = step.compensate(context);

            if (result) {
                sagaStep.setStatus(StepStatus.COMPENSATED);
                sagaStepRepository.save(sagaStep);
                log.info("Saga step {} compensated for sagaInstanceId {}", stepName, sagaInstanceId);
                return true;
            } else {
                sagaStep.setStatus(StepStatus.FAILED);
                sagaStepRepository.save(sagaStep);
                log.error("Saga step {} compensation failed for sagaInstanceId {}", stepName, sagaInstanceId);
                return false;
            }
        } catch (Exception e) {
            sagaStep.setStatus(StepStatus.FAILED);
            sagaStepRepository.save(sagaStep);
            log.error("Saga step {} compensation failed for sagaInstanceId {} with error: {}", stepName, sagaInstanceId,
                    e.getMessage());
            return false;
        }
    }

    @Override
    public SagaInstance getSagaInstance(Long sagaInstanceId) {
        // Get saga instance implementation
        return null;
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
            if (allStepsCompensated) {
                sagaInstance.setStatus(SagaStatus.COMPENSATED);
                sagaInstanceRepository.save(sagaInstance);
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
