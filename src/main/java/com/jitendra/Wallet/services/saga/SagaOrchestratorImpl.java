package com.jitendra.Wallet.services.saga;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jitendra.Wallet.entity.SagaInstance;
import com.jitendra.Wallet.entity.SagaStatus;
import com.jitendra.Wallet.repository.SagaInstanceRepository;

import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Slf4j
public class SagaOrchestratorImpl implements SagaOrchestrator {
    // Implementation details...

    private final ObjectMapper objectMapper;
    private final SagaInstanceRepository sagaInstanceRepository;

    @Override
    public Long startSaga(SagaContext context) {
        try{
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
        }
        catch(Exception e){
            log.error("Failed to start saga: {}", e.getMessage());
            throw new RuntimeException("Failed to start saga", e);
        }
    }

    @Override
    public boolean executeStep(Long sagaInstanceId) {
        // Execute step implementation
        return false;
    }

    @Override
    public boolean compensateStep(Long sagaInstanceId) {
        // Compensate step implementation
        return false;
    }
    @Override
    public SagaInstance getSagaInstance(Long sagaInstanceId) {
        // Get saga instance implementation
        return null;
    }
    @Override
    public void compensateSaga(Long sagaInstanceId) {
        // Compensate saga implementation
    }

    @Override
    public void failSaga(Long sagaInstanceId) {
        // Fail saga implementation
    }

    @Override
    public void completeSaga(Long sagaInstanceId) {
        // Complete saga implementation
    }
    
}
