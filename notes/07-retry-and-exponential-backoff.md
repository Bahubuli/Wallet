# Retry Mechanisms & Exponential Backoff

## Quick Summary

**Retry** = Automatically re-attempting a failed operation, assuming the failure is transient.
**Exponential Backoff** = Each retry waits longer than the previous one (1s → 2s → 4s → 8s), reducing load on the struggling system.

---

## 1. Why Retry?

Not all failures are permanent. **Transient failures** are temporary and self-resolving:

| Transient Failure | Why It Happens | Will Resolve? |
|-------------------|---------------|---------------|
| `ObjectOptimisticLockingFailureException` | Two transactions modified same row | ✅ Yes (other tx will finish) |
| `CannotAcquireLockException` | Row locked by another transaction | ✅ Yes (lock will release) |
| `TransientDataAccessException` | Brief network blip, connection reset | ✅ Yes (network recovers) |
| Connection timeout | Database under load | ✅ Probably (load is temporary) |

**Permanent failures** should NOT be retried:

| Permanent Failure | Why |
|-------------------|-----|
| `ResourceNotFoundException` | Resource doesn't exist — retrying won't create it |
| `ConstraintViolationException` | Data violates DB constraint — same data will always fail |
| `NullPointerException` | Code bug — retrying the same code won't fix it |
| Business validation failure | "Insufficient balance" — retrying with same amount will always fail |

---

## 2. Retry Strategies

### Strategy 1: Fixed Delay

```
Attempt 1 → fail → wait 2s →
Attempt 2 → fail → wait 2s →
Attempt 3 → fail → give up

Timeline: ─────|──2s──|──2s──|──X
```

**Problem:** If 1000 clients all retry after exactly 2 seconds → **thundering herd** overloads the server again.

### Strategy 2: Exponential Backoff ← **Our Approach**

```
Attempt 1 → fail → wait 1s →
Attempt 2 → fail → wait 2s →
Attempt 3 → fail → wait 4s →
Attempt 4 → fail → wait 8s →
Attempt 5 → fail → give up

Timeline: ─|─1s─|──2s──|────4s────|────────8s────────|──X
```

**Each wait doubles.** This gives the failing system progressively more time to recover.

### Strategy 3: Exponential Backoff + Jitter

```
Backoff = min(maxInterval, initialInterval × 2^attempt) + random(0, jitter)

Attempt 1: 1s + random(0, 500ms) = 1.3s
Attempt 2: 2s + random(0, 500ms) = 2.1s
Attempt 3: 4s + random(0, 500ms) = 4.4s
```

**Jitter** spreads out retries so multiple clients don't retry at the exact same time.

```
Without jitter (thundering herd):
Client A: ─────|──1s──|──retry
Client B: ─────|──1s──|──retry    ← Both hit server at same time!
Client C: ─────|──1s──|──retry

With jitter:
Client A: ─────|──1.3s──|──retry
Client B: ─────|──0.8s──|──retry   ← Spread out!
Client C: ─────|──1.5s──|──retry
```

---

## 3. Our Implementation — Spring RetryTemplate

### Code

```java
// SagaOrchestratorImpl.java

// 1. Define which exceptions are retryable
private static final Map<Class<? extends Throwable>, Boolean> TRANSIENT_EXCEPTIONS;
static {
    TRANSIENT_EXCEPTIONS = new HashMap<>();
    TRANSIENT_EXCEPTIONS.put(ObjectOptimisticLockingFailureException.class, true);
    TRANSIENT_EXCEPTIONS.put(CannotAcquireLockException.class, true);
    TRANSIENT_EXCEPTIONS.put(TransientDataAccessException.class, true);
}

// 2. Build retry template with exponential backoff
private RetryTemplate buildRetryTemplate(int maxAttempts) {
    SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(
        maxAttempts,           // max 3 attempts
        TRANSIENT_EXCEPTIONS,  // only retry these exceptions
        true                   // traverse cause chain (check nested exceptions too)
    );

    ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
    backOff.setInitialInterval(1_000L);   // Start at 1 second
    backOff.setMultiplier(2.0);           // Double each time
    backOff.setMaxInterval(10_000L);      // Cap at 10 seconds

    RetryTemplate rt = new RetryTemplate();
    rt.setRetryPolicy(retryPolicy);
    rt.setBackOffPolicy(backOff);
    return rt;
}
```

### Backoff Timeline

```
maxAttempts = 3, initialInterval = 1s, multiplier = 2.0, maxInterval = 10s

Attempt 1: Execute step
   ❌ OptimisticLockingFailureException
   Wait: min(1000 × 2^0, 10000) = 1000ms (1s)

Attempt 2: Execute step (fresh read from DB)
   ❌ OptimisticLockingFailureException
   Wait: min(1000 × 2^1, 10000) = 2000ms (2s)

Attempt 3: Execute step (fresh read from DB)
   ✅ Success! (or ❌ → exhausted → recovery callback)
```

### Usage in Step Execution

```java
@Override
public boolean executeStep(Long sagaInstanceId, String stepName, Integer stepOrder) {
    // ... setup SagaInstance, SagaStep ...

    RetryTemplate rt = buildRetryTemplate(sagaStep.getMaxRetries());

    boolean result = rt.execute(
        // RetryCallback — executed on each attempt
        (RetryCallback<Boolean, Exception>) retryContext -> {
            if (retryContext.getRetryCount() > 0) {
                // Track retry count on the step entity
                sagaStep.setRetryCount(sagaStep.getRetryCount() + 1);
                sagaStepRepository.save(sagaStep);
            }
            return step.execute(ctx);  // Actual step execution
        },

        // RecoveryCallback — executed when all retries exhausted
        (RecoveryCallback<Boolean>) recoveryContext -> {
            if (recoveryContext.getLastThrowable() != null) {
                sagaStep.setErrorMessage(recoveryContext.getLastThrowable().getMessage());
            }
            return false;  // Step failed after all retries
        }
    );
}
```

### Key Design Decisions

```
1. Per-step retry: Each saga step has its own maxRetries (default 3)
2. Retry count persisted: sagaStep.retryCount tracks attempts in DB
3. Recovery callback: Captures error details for debugging
4. Transient-only: Only retries known-transient exceptions
5. noRollbackFor: Transaction annotation prevents rollback on transient exceptions
```

```java
@Transactional(noRollbackFor = {
    TransientDataAccessException.class,
    ObjectOptimisticLockingFailureException.class,
    CannotAcquireLockException.class
})
public boolean executeStep(...) {
    // Spring won't rollback the transaction for these exceptions
    // allowing the retry template to handle them
}
```

---

## 4. RetryTemplate Components

```
┌─────────────────────────────────────────┐
│             RetryTemplate               │
│                                         │
│  ┌─────────────────┐                   │
│  │   RetryPolicy   │                   │
│  │  (SimpleRetry)  │                   │
│  │  maxAttempts: 3  │                   │
│  │  retryableExns   │                   │
│  └────────┬────────┘                   │
│           │                             │
│  ┌────────▼────────┐                   │
│  │ BackOffPolicy   │                   │
│  │ (Exponential)   │                   │
│  │ initial: 1s     │                   │
│  │ multiplier: 2x  │                   │
│  │ max: 10s        │                   │
│  └────────┬────────┘                   │
│           │                             │
│  ┌────────▼────────┐                   │
│  │ RetryCallback   │ ← Your code       │
│  │ (execute step)  │                   │
│  └────────┬────────┘                   │
│           │ all retries failed          │
│  ┌────────▼────────┐                   │
│  │RecoveryCallback │ ← Fallback        │
│  │(capture error)  │                   │
│  └─────────────────┘                   │
└─────────────────────────────────────────┘
```

### Available Retry Policies

| Policy | Behavior |
|--------|----------|
| `SimpleRetryPolicy` | Fixed max attempts, retryable exception map |
| `MaxAttemptsRetryPolicy` | Just max attempts, any exception |
| `TimeoutRetryPolicy` | Retry until time limit reached |
| `CircuitBreakerRetryPolicy` | Open circuit after too many failures |
| `CompositeRetryPolicy` | Combine multiple policies (AND/OR) |
| `NeverRetryPolicy` | Never retry (useful for testing) |
| `AlwaysRetryPolicy` | Always retry (careful — infinite!) |

### Available Backoff Policies

| Policy | Wait Pattern |
|--------|-------------|
| `FixedBackOffPolicy` | Same delay every time (1s, 1s, 1s) |
| `ExponentialBackOffPolicy` | Doubles: 1s, 2s, 4s, 8s |
| `ExponentialRandomBackOffPolicy` | Exponential + jitter |
| `UniformRandomBackOffPolicy` | Random within range |
| `NoBackOffPolicy` | No wait (immediate retry) |

---

## 5. Retry Patterns for Different Scenarios

### Pattern 1: Optimistic Lock Retry (Our Use Case)

```java
// Each retry does a FRESH READ from the database
retryTemplate.execute(ctx -> {
    Wallet wallet = walletRepository.findById(id).orElseThrow();  // Fresh read
    wallet.debit(amount);
    walletRepository.save(wallet);  // Might throw OptimisticLockException
    return true;
});
```

Critical: The **read must be inside the retry** so we get the latest version.

### Pattern 2: External Service Call

```java
RetryTemplate rt = new RetryTemplate();
rt.setRetryPolicy(new SimpleRetryPolicy(3, Map.of(
    HttpServerErrorException.class, true,   // 5xx errors
    ResourceAccessException.class, true     // connection errors
)));

rt.execute(ctx -> {
    return restTemplate.postForEntity(url, body, Response.class);
});
```

### Pattern 3: Circuit Breaker + Retry

```
Closed (normal):    retry on failure
Half-Open:          allow one request through
Open:               fail immediately (no retry)

State transitions:
  CLOSED ──(failures > threshold)──► OPEN
  OPEN ──(timeout expires)──► HALF_OPEN
  HALF_OPEN ──(success)──► CLOSED
  HALF_OPEN ──(failure)──► OPEN
```

---

## 6. Common Pitfalls

### Pitfall 1: Retrying Non-Idempotent Operations

```java
// ❌ DANGEROUS: Retrying a non-idempotent operation
retryTemplate.execute(ctx -> {
    paymentService.charge(userId, amount);  // Might charge twice!
    return true;
});

// ✅ SAFE: Make it idempotent with a unique key
retryTemplate.execute(ctx -> {
    paymentService.charge(userId, amount, idempotencyKey);
    return true;
});
```

### Pitfall 2: Retrying Permanent Errors

```java
// ❌ Wasting time retrying a permanent error
retryTemplate.execute(ctx -> {
    walletRepository.findById(nonExistentId).orElseThrow();  // Will ALWAYS throw
    return true;
});

// ✅ Only retry transient exceptions
new SimpleRetryPolicy(3, Map.of(
    TransientDataAccessException.class, true
    // ResourceNotFoundException is NOT retryable
));
```

### Pitfall 3: No Backoff (Retry Storm)

```
Without backoff:
  100 clients fail → 100 instant retries → server more overloaded → 100 more retries → crash

With exponential backoff:
  100 clients fail → 100 wait 1s → 50 succeed → 50 wait 2s → 30 succeed → ...
  Load gradually decreases, server recovers
```

### Pitfall 4: Stale Data in Retry

```java
// ❌ Reading data OUTSIDE retry — stale on retry
Wallet wallet = walletRepository.findById(id).orElseThrow();
retryTemplate.execute(ctx -> {
    wallet.debit(amount);       // Same (stale) entity on every retry
    walletRepository.save(wallet);  // Version will never match after first failure
    return true;
});

// ✅ Reading data INSIDE retry — fresh on each attempt
retryTemplate.execute(ctx -> {
    Wallet wallet = walletRepository.findById(id).orElseThrow();  // Fresh read
    wallet.debit(amount);
    walletRepository.save(wallet);
    return true;
});
```

---

## 7. Exponential Backoff Math

```
wait_time(n) = min(initialInterval × multiplier^(n-1), maxInterval)

Our config: initial=1000ms, multiplier=2, max=10000ms

Attempt 1: min(1000 × 2^0, 10000) = min(1000, 10000) = 1000ms
Attempt 2: min(1000 × 2^1, 10000) = min(2000, 10000) = 2000ms
Attempt 3: min(1000 × 2^2, 10000) = min(4000, 10000) = 4000ms
Attempt 4: min(1000 × 2^3, 10000) = min(8000, 10000) = 8000ms
Attempt 5: min(1000 × 2^4, 10000) = min(16000, 10000) = 10000ms  ← capped
Attempt 6: min(1000 × 2^5, 10000) = min(32000, 10000) = 10000ms  ← capped

Total max wait: 1 + 2 + 4 + 8 + 10 + 10 + ... = grows linearly after cap
```

---

## 8. Interview Quick-Fire

**Q: Why exponential backoff instead of fixed delay?**
A: Exponential backoff reduces load on the failing system progressively. If the system is overloaded, fixed-delay retries keep hitting it at the same rate. Exponential backoff gives it exponentially more breathing room.

**Q: What is jitter and why is it important?**
A: Jitter adds randomness to retry timing to prevent all clients from retrying simultaneously (thundering herd). Without jitter, N clients failing at the same time will all retry at the same time.

**Q: What's the difference between retry and circuit breaker?**
A: Retry waits and tries again. Circuit breaker **stops trying** after too many failures (opens the circuit), preventing a cascade of failing calls. They complement each other: circuit breaker wraps retry.

**Q: How do you make retries safe?**
A: Ensure operations are **idempotent** (same result when repeated). Use idempotency keys, check-then-act patterns, or state checks before executing.

**Q: How does Spring Retry differ from resilience4j?**
A: Spring Retry uses `RetryTemplate` (programmatic) or `@Retryable` (annotation-based). Resilience4j is a standalone library with retry, circuit breaker, rate limiter, and bulkhead all in one. Resilience4j is more feature-rich; Spring Retry is simpler for basic cases.

---

## Key Takeaway

> We use **Spring RetryTemplate with exponential backoff** (1s → 2s → 4s, capped at 10s) to handle transient failures in saga steps. Only **three specific transient exceptions** (optimistic lock, lock acquisition, transient DB) trigger retries. Each step's retry count is **persisted in the database** for observability. If all retries are exhausted, the step is marked FAILED and the saga begins compensation.
