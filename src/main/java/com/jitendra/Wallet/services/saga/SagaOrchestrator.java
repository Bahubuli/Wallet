package com.jitendra.Wallet.services.saga;

import com.jitendra.Wallet.entity.SagaInstance;

public interface SagaOrchestrator {
    
    Long startSaga(SagaContext context);

    boolean executeStep(Long sagaInstanceId);

    boolean compensateStep(Long sagaInstanceId);

    SagaInstance getSagaInstance(Long sagaInstanceId);

    // compensate all steps done till now 
    void compensateSaga(Long sagaInstanceId);

    void failSaga(Long sagaInstanceId);

    void completeSaga(Long sagaInstanceId);
}
