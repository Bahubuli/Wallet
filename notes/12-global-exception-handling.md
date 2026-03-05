# Global Exception Handling & Error Responses

## Quick Summary

**Global Exception Handling** = A centralized mechanism (`@RestControllerAdvice`) that intercepts all exceptions thrown by controllers and converts them into consistent, structured HTTP error responses — so individual controllers never handle exceptions themselves.

---

## 1. The Problem: Without Centralized Handling

```java
// ❌ BAD: Every controller has its own try-catch
@PostMapping("/create")
public ResponseEntity<?> createWallet(@RequestBody WalletRequestDTO req) {
    try {
        return ResponseEntity.ok(walletService.create(req));
    } catch (BusinessException e) {
        return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
    } catch (ResourceNotFoundException e) {
        return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
    } catch (Exception e) {
        return ResponseEntity.status(500).body(Map.of("error", "Internal error"));
    }
}
// Repeated in EVERY controller action → DRY violation, inconsistent formats
```

---

## 2. The Solution: @RestControllerAdvice

```
┌─────────────────────────────────────────────┐
│              Client Request                  │
└───────────────────┬─────────────────────────┘
                    ▼
┌─────────────────────────────────────────────┐
│              Controller                      │
│  (No try-catch, just throws exceptions)      │
└───────────────────┬─────────────────────────┘
                    │ Exception thrown
                    ▼
┌─────────────────────────────────────────────┐
│       @RestControllerAdvice                  │
│       GlobalExceptionHandler                 │
│                                              │
│  @ExceptionHandler(BusinessException.class)  │
│    → 400 Bad Request                         │
│                                              │
│  @ExceptionHandler(ResourceNotFound.class)   │
│    → 404 Not Found                           │
│                                              │
│  @ExceptionHandler(Validation.class)         │
│    → 400 Bad Request + field errors          │
│                                              │
│  @ExceptionHandler(Exception.class)          │
│    → 500 Internal Server Error (catch-all)   │
└───────────────────┬─────────────────────────┘
                    ▼
┌─────────────────────────────────────────────┐
│        Structured ErrorResponseDTO           │
│  { status, error, message, timestamp }       │
└─────────────────────────────────────────────┘
```

`@RestControllerAdvice` = `@ControllerAdvice` + `@ResponseBody` — every handler method's return value is serialized to JSON automatically.

---

## 3. Our Implementation

### Exception Hierarchy

```java
// Base business exception — for all domain-level violations
public class BusinessException extends RuntimeException {
    public BusinessException(String message) {
        super(message);
    }
}

// Specific exception — resource not found (404)
public class ResourceNotFoundException extends RuntimeException {
    public ResourceNotFoundException(String message) {
        super(message);
    }
}
```

### Structured Error Response

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ErrorResponseDTO {
    private int status;         // HTTP status code (400, 404, 500)
    private String error;       // Category ("Business Error", "Not Found")
    private String message;     // Details ("Insufficient balance in wallet 42")
    private Instant timestamp;  // When the error occurred
}
```

### GlobalExceptionHandler

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    // ── Business Rule Violations → 400 ──
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponseDTO> handleBusinessException(BusinessException ex) {
        return buildResponse(HttpStatus.BAD_REQUEST, "Business Error", ex.getMessage());
    }

    // ── Resource Not Found → 404 ──
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponseDTO> handleResourceNotFound(ResourceNotFoundException ex) {
        return buildResponse(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage());
    }

    // ── Bean Validation Failures → 400 ──
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponseDTO> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining("; "));
        return buildResponse(HttpStatus.BAD_REQUEST, "Validation Failed", message);
    }

    // ── Catch-All → 500 ──
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponseDTO> handleGeneral(Exception ex) {
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, 
            "Internal Server Error", ex.getMessage());
    }

    // ── Helper ──
    private ResponseEntity<ErrorResponseDTO> buildResponse(
            HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(
            ErrorResponseDTO.builder()
                .status(status.value())
                .error(error)
                .message(message)
                .timestamp(Instant.now())
                .build()
        );
    }
}
```

---

## 4. Exception Matching Order

Spring resolves `@ExceptionHandler` by **specificity** — most specific exception class wins:

```
Exception thrown: BusinessException("Insufficient balance")

Candidates:
  handleBusinessException(BusinessException)    ← MATCH (exact)
  handleGeneral(Exception)                      ← MATCH (parent class)

Winner: handleBusinessException (most specific)
```

```
Specificity ladder (most specific → least):
  ResourceNotFoundException
  BusinessException
  MethodArgumentNotValidException
  RuntimeException
  Exception  ← catch-all / safety net
```

---

## 5. Where Exceptions Are Thrown

### In Services (Business & Not Found)

```java
// WalletService
public WalletResponseDTO getWalletById(Long id) {
    Wallet w = walletRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException(
            "Wallet not found with id: " + id));    // → 404
    return mapToResponseDTO(w);
}

public void debit(Long walletId, BigDecimal amount) {
    Wallet w = walletRepository.findById(walletId)
        .orElseThrow(() -> new ResourceNotFoundException(...));
    if (!w.hasSufficientBalance(amount))
        throw new BusinessException(
            "Insufficient balance in wallet: " + walletId);  // → 400
    w.debit(amount);
    walletRepository.save(w);
}
```

### In Controllers (Implicitly via @Valid)

```java
// @Valid triggers MethodArgumentNotValidException if DTO is invalid
@PostMapping("/create")
public ResponseEntity<WalletResponseDTO> createWallet(
    @Valid @RequestBody WalletRequestDTO req) {  // → 400 if invalid
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(walletService.createWallet(req));
}
```

### In Saga Layer (Retryable exceptions)

```java
// SagaOrchestratorImpl — some exceptions are retried, others thrown
private static final Map<Class<? extends Exception>, Boolean> TRANSIENT_EXCEPTIONS = Map.of(
    ObjectOptimisticLockingFailureException.class, true,
    CannotAcquireLockException.class, true,
    TransientDataAccessException.class, true
);
// If retries exhausted → exception propagates → GlobalExceptionHandler catches → 500
```

---

## 6. Extending the Exception Hierarchy

### For New Feature (e.g., rate limiting)

```java
// Step 1: Create exception
public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String message) { super(message); }
}

// Step 2: Add handler
@ExceptionHandler(RateLimitExceededException.class)
public ResponseEntity<ErrorResponseDTO> handleRateLimit(RateLimitExceededException ex) {
    return buildResponse(HttpStatus.TOO_MANY_REQUESTS, "Rate Limit Exceeded", ex.getMessage());
}
```

### Common HTTP Status Codes for APIs

| Code | When to Use | Our Exception |
|------|------------|---------------|
| **400** | Invalid input, business rule violation | `BusinessException`, `MethodArgumentNotValidException` |
| **401** | Unauthenticated | `AuthenticationException` |
| **403** | Unauthorized (no permission) | `AccessDeniedException` |
| **404** | Resource doesn't exist | `ResourceNotFoundException` |
| **409** | Conflict (e.g., duplicate, version mismatch) | `OptimisticLockException` |
| **422** | Semantically invalid (valid syntax, bad logic) | `BusinessException` variant |
| **429** | Rate limit exceeded | `RateLimitExceededException` |
| **500** | Unexpected server error | `Exception` (catch-all) |
| **503** | Service unavailable | `ServiceUnavailableException` |

---

## 7. @ControllerAdvice vs @RestControllerAdvice

| Feature | `@ControllerAdvice` | `@RestControllerAdvice` |
|---------|-------------------|----------------------|
| Response body | Must add `@ResponseBody` per method | Implicit `@ResponseBody` on all methods |
| Use case | Server-rendered views (Thymeleaf) | REST APIs (JSON responses) |
| Equivalent | `@ControllerAdvice` + `@ResponseBody` | `@RestControllerAdvice` |

---

## 8. Anti-Patterns

```
❌ Catching Exception in controller and returning generic message
   → Use specific exception classes, let GlobalExceptionHandler handle it

❌ Leaking stack traces to clients
   → Only return message, not stacktrace
   → Log full stacktrace server-side: log.error("Error", ex)

❌ Using HTTP 200 for errors with error body
   → Always use proper HTTP status codes (4xx, 5xx)

❌ Inconsistent error formats across endpoints
   → Use a single ErrorResponseDTO for ALL error responses

❌ Business logic in exception handler
   → Handlers should only FORMAT the response, not make business decisions
```

---

## 9. Interview Quick-Fire

**Q: What's the difference between @ControllerAdvice and @RestControllerAdvice?**
A: `@RestControllerAdvice` = `@ControllerAdvice` + `@ResponseBody`. The REST variant automatically serializes return values to JSON. Use `@RestControllerAdvice` for APIs, `@ControllerAdvice` for MVC views.

**Q: How does Spring determine which @ExceptionHandler to invoke?**
A: By exception class specificity — the handler matching the most specific exception type wins. If an `InsufficientBalanceException extends BusinessException` is thrown, a handler for `InsufficientBalanceException` is chosen over one for `BusinessException`.

**Q: Should you catch checked or unchecked exceptions in service layer?**
A: In Spring, prefer **unchecked exceptions** (`RuntimeException` subclasses). They don't force callers to handle them, and `@Transactional` rolls back by default only for unchecked exceptions.

**Q: How would you handle different error formats for different API versions?**
A: Use `@RestControllerAdvice(basePackages = "com.app.v1")` to scope exception handlers to specific controller packages.

---

## Key Takeaway

> We use **`@RestControllerAdvice`** with a single `GlobalExceptionHandler` to centralize all error handling. **Custom exception classes** (`BusinessException`, `ResourceNotFoundException`) map to specific HTTP status codes, while a structured **`ErrorResponseDTO`** ensures consistent error response format across all endpoints. Controllers never contain try-catch blocks — they simply throw, and the global handler formats the response.
