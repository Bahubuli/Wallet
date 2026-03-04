package com.jitendra.Wallet.exception;

/**
 * Thrown when a requested resource (Transaction, Wallet, User) does not exist.
 * Maps to HTTP 404 Not Found in the GlobalExceptionHandler.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
