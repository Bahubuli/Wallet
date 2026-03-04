package com.jitendra.Wallet.exception;

/**
 * Thrown for deliberate business-rule violations (e.g. inactive wallet,
 * same source/destination, insufficient funds).
 * Maps to HTTP 400 Bad Request in the GlobalExceptionHandler.
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
