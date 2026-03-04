package com.jitendra.Wallet.exception;

import java.time.Instant;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.jitendra.Wallet.dto.ErrorResponseDTO;

import lombok.extern.slf4j.Slf4j;

/**
 * GlobalExceptionHandler — the single place where ALL exceptions are caught,
 * logged, and converted into a structured {@link ErrorResponseDTO} JSON body.
 *
 * HOW IT WORKS:
 * -----------
 * 
 * @RestControllerAdvice makes Spring register this class as an interceptor that
 *                       wraps every @RestController in the application. Before
 *                       Spring serialises the
 *                       response, it checks whether an exception was thrown. If
 *                       it was, Spring looks
 *                       for an @ExceptionHandler method here that matches the
 *                       exception type (most
 *                       specific match wins). That method builds and returns a
 *                       ResponseEntity — Spring
 *                       never gets to write the original error; it writes our
 *                       clean response instead.
 *
 *                       HANDLER PRIORITY (most specific → least specific):
 *                       ---------------------------------------------------
 *                       1. MethodArgumentNotValidException → 400 (@Valid bean
 *                       validation failures)
 *                       2. ResourceNotFoundException → 404 (entity not found in
 *                       DB)
 *                       3. BusinessException → 400 (deliberate business-rule
 *                       violation)
 *                       4. Exception (catch-all) → 500 (anything unexpected)
 *
 *                       WHY SEPARATE EXCEPTION TYPES MATTER:
 *                       ------------------------------------
 *                       A naked RuntimeException ("Transaction not found")
 *                       cannot be distinguished
 *                       from a RuntimeException caused by a DB timeout. Custom
 *                       exception types let
 *                       us map each to the correct HTTP status code precisely.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // -------------------------------------------------------------------------
    // 1. Validation failures — @Valid on @RequestBody
    // HTTP 400: the client sent data that violates our constraints
    // -------------------------------------------------------------------------

    /**
     * Triggered when a @RequestBody fails @Valid bean validation.
     * Collects EVERY field-level constraint violation into a single
     * semicolon-separated message so the caller sees ALL broken fields at once.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDTO> handleValidationException(
            MethodArgumentNotValidException ex) {

        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));

        log.warn("Validation failed: {}", message);

        return buildResponse(HttpStatus.BAD_REQUEST, "Validation Failed", message);
    }

    // -------------------------------------------------------------------------
    // 2. Resource not found
    // HTTP 404: the thing the client asked for simply does not exist
    // -------------------------------------------------------------------------

    /**
     * Thrown explicitly by service/repository code when an entity is not found,
     * e.g. throw new ResourceNotFoundException("Transaction not found with id: 5").
     * Returning 404 here is semantically correct — 400 would be wrong.
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponseDTO> handleResourceNotFoundException(
            ResourceNotFoundException ex) {

        log.warn("Resource not found: {}", ex.getMessage());

        return buildResponse(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
    }

    // -------------------------------------------------------------------------
    // 3. Business rule violations
    // HTTP 400: the client's request is structurally valid but breaks a rule
    // -------------------------------------------------------------------------

    /**
     * Thrown when a deliberate business constraint is violated:
     * - wallet is inactive
     * - source == destination wallet
     * - insufficient funds
     * These ARE the client's fault, so 400 is correct here.
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponseDTO> handleBusinessException(BusinessException ex) {

        log.warn("Business rule violation: {}", ex.getMessage());

        return buildResponse(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage());
    }

    // -------------------------------------------------------------------------
    // 4. Catch-all — anything not matched above
    // HTTP 500: something broke that we didn't anticipate
    // -------------------------------------------------------------------------

    /**
     * Safety net. Logs the full stack trace (critical for debugging) but returns
     * ONLY a generic message to the client — never leak implementation details.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDTO> handleGenericException(Exception ex) {

        log.error("Unexpected server error: {}", ex.getMessage(), ex);

        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal Server Error",
                "An unexpected error occurred. Please try again later.");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private ResponseEntity<ErrorResponseDTO> buildResponse(
            HttpStatus status, String error, String message) {

        ErrorResponseDTO body = ErrorResponseDTO.builder()
                .status(status.value())
                .error(error)
                .message(message)
                .timestamp(Instant.now())
                .build();

        return ResponseEntity.status(status).body(body);
    }
}
