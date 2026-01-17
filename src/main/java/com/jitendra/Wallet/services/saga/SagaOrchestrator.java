package com.jitendra.Wallet.services.saga;

import com.jitendra.Wallet.entity.SagaInstance;

public interface SagaOrchestrator {
    
    Long startSaga(SagaContext context);

    boolean executeStep(Long sagaInstanceId, String stepName);

    boolean compensateStep(Long sagaInstanceId, String stepName);

    SagaInstance getSagaInstance(Long sagaInstanceId);

    // compensate all steps done till now 
    void compensateSaga(Long sagaInstanceId);

    void failSaga(Long sagaInstanceId);

    void completeSaga(Long sagaInstanceId);
}
