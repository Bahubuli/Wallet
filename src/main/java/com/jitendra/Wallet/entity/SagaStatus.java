package com.jitendra.Wallet.entity;

public enum SagaStatus {
    STARTED, 
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    COMPENSATING,
    COMPENSATED
}