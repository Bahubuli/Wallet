package com.jitendra.Wallet.dto;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Structured error response returned by the GlobalExceptionHandler.
 *
 * Every error — validation failures, not-found, server errors — is returned in
 * this exact shape so API consumers can always parse the same contract:
 *
 * {
 * "status": 400,
 * "error": "Validation Failed",
 * "message": "Amount must be greater than 0",
 * "timestamp": "2026-03-04T10:38:22.123456Z"
 * }
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponseDTO {

    /** HTTP status code (mirrors the HTTP response status). */
    private int status;

    /**
     * Short human-readable category of the error (e.g. "Validation Failed", "Not
     * Found").
     */
    private String error;

    /** Detailed developer/user-facing message explaining what went wrong. */
    private String message;

    /** UTC instant when the error occurred. */
    private Instant timestamp;
}
