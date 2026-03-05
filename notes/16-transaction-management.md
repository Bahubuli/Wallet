# Transaction Management & @Transactional Patterns

## Quick Summary

**@Transactional** = Spring annotation that wraps a method in a database transaction — auto-commits on success, auto-rollbacks on `RuntimeException`.
**TransactionTemplate** = Programmatic alternative — gives manual control over transaction boundaries when declarative `@Transactional` isn't flexible enough.

---

## 1. Declarative vs Programmatic Transactions

```
DECLARATIVE (@Transactional):
  @Transactional
  public void transfer(Long from, Long to, BigDecimal amount) {
      walletRepository.debit(from, amount);     // If this succeeds
      walletRepository.credit(to, amount);      // but this fails...
      // → entire method rolls back (debit reversed)
  }
  
  ✅ Clean, simple, annotation-driven
  ❌ Less control over exact boundaries

PROGRAMMATIC (TransactionTemplate):
  transactionTemplate.execute(status -> {
      walletRepository.debit(from, amount);
      walletRepository.credit(to, amount);
      return null;
  });
  
  ✅ Precise control over what's in the transaction
  ✅ Can mix transactional and non-transactional code
  ❌ More verbose
```

---

## 2. How @Transactional Works (Proxy)

```
┌─────────────────────────────────────────────────┐
│                  Call Flow                        │
│                                                   │
│  Controller                                       │
│      │                                            │
│      ▼                                            │
│  Spring AOP Proxy (TransactionInterceptor)       │
│      │  1. Get DB connection                      │
│      │  2. BEGIN TRANSACTION                      │
│      ▼                                            │
│  Actual Service Method                            │
│      │  (your business logic executes)            │
│      │                                            │
│      ▼                                            │
│  Spring AOP Proxy                                 │
│      │  3a. No exception → COMMIT                │
│      │  3b. RuntimeException → ROLLBACK          │
│      │  3c. Checked exception → COMMIT (default!) │
└─────────────────────────────────────────────────┘
```

**Critical**: `@Transactional` only works when the method is called **from outside the class** (through the proxy). Self-invocation bypasses the proxy!

```java
@Service
public class WalletService {
    
    @Transactional
    public void methodA() {
        methodB();  // ❌ Self-call — @Transactional on methodB is IGNORED
    }
    
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void methodB() { ... }
}
```

---

## 3. Our Transactional Usage Patterns

### Pattern 1: Service-Layer @Transactional (Read-Write)

```java
@Service
public class WalletService {
    
    @Transactional  // Each write operation is atomic
    public WalletResponseDTO createWallet(WalletRequestDTO req) {
        User user = userRepository.findById(req.getUserId())
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        
        Wallet w = new Wallet();
        w.setUserId(req.getUserId());
        w.setBalance(req.getBalance());
        Wallet saved = walletRepository.save(w);
        return mapToResponseDTO(saved);
    }
    
    @Transactional  // Debit + save are atomic
    public WalletResponseDTO debitWallet(Long walletId, BigDecimal amount) {
        Wallet w = walletRepository.findById(walletId)
            .orElseThrow(() -> new ResourceNotFoundException("Wallet not found"));
        
        if (!w.hasSufficientBalance(amount))
            throw new BusinessException("Insufficient balance");  // → ROLLBACK
        
        w.debit(amount);
        walletRepository.save(w);
        return mapToResponseDTO(w);
    }
}
```

### Pattern 2: Read-Only Transactions

```java
@Transactional(readOnly = true)  // Hint to DB: no writes allowed
public WalletResponseDTO getWalletById(Long id) {
    Wallet w = walletRepository.findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Wallet not found"));
    return mapToResponseDTO(w);
}
```

**Benefits of `readOnly = true`:**
- Hibernate skips dirty-checking (no need to detect changes)
- Some DB drivers optimize connection (read replica routing)
- PostgreSQL may use a lighter transaction mode

### Pattern 3: @Transactional with noRollbackFor

```java
// SagaOrchestratorImpl
@Transactional(noRollbackFor = {
    BusinessException.class,
    ResourceNotFoundException.class
})
public SagaInstance executeStep(SagaInstance saga, SagaStep step) {
    try {
        sagaStep.execute(saga.getContext());
        step.setStatus(StepStatus.COMPLETED);
    } catch (BusinessException e) {
        step.setStatus(StepStatus.FAILED);
        step.setErrorMessage(e.getMessage());
        // DON'T rollback — we want to SAVE the failure status
        // The saga compensation will handle the business reversal
    }
    sagaStepRepository.save(step);  // This save persists even on BusinessException
    return sagaInstanceRepository.save(saga);
}
```

**Why?** In the saga pattern, a step failure is NOT a database error — it's a business event that needs to be recorded. We want the transaction to commit so the failure status is saved.

### Pattern 4: TransactionTemplate (Programmatic)

```java
@Service
public class TransferSagaService {
    
    private final TransactionTemplate transactionTemplate;
    
    public TransactionResponseDTO initiateTransfer(TransactionRequestDTO req) {
        // Step 1: Create saga + transaction atomically (programmatic TX)
        SagaInstance saga = transactionTemplate.execute(status -> {
            Transaction tx = createTransaction(req);
            return createSagaWithTransaction(tx, req);
        });
        
        // Step 2: Execute steps (each step has its own TX via @Transactional)
        executeSagaSteps(saga);
        
        // Step 3: Build response (non-transactional)
        return buildResponse(saga);
    }
}
```

**Why TransactionTemplate here?** We need the saga initialization to be atomic, but we DON'T want the step execution to be in the same transaction. If `initiateTransfer` were `@Transactional`, the entire method (init + all steps) would be one huge transaction.

---

## 4. Transaction Propagation

What happens when a `@Transactional` method calls another `@Transactional` method?

| Propagation | Behavior | Use Case |
|-------------|----------|----------|
| **REQUIRED** (default) | Join existing TX, or create new if none | Most service methods |
| **REQUIRES_NEW** | Suspend existing TX, create new | Audit logging, independent operations |
| **MANDATORY** | Must have existing TX, error if none | Enforce caller has TX |
| **SUPPORTS** | Join if exists, run without TX if none | Read operations |
| **NOT_SUPPORTED** | Suspend existing TX, run without TX | Heavy computations |
| **NEVER** | Error if TX exists | Ensure non-transactional execution |
| **NESTED** | Savepoint within existing TX | Partial rollbacks |

```java
// Default: REQUIRED
@Transactional  // Joins caller's TX or creates new
public void debitWallet(...) { ... }

// REQUIRES_NEW: Always new TX
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void logAudit(...) { ... }  // Committed even if caller rolls back
```

### Propagation Flow

```
Service A (@Transactional)
  │
  ├─ calls Service B (@Transactional) 
  │    → REQUIRED: joins A's transaction
  │    → REQUIRES_NEW: suspends A's TX, creates new TX for B
  │
  ├─ calls Service C (@Transactional(propagation = MANDATORY))
  │    → OK: A has an active TX
  │
  └─ if A throws RuntimeException:
       → REQUIRED: both A and B roll back (same TX)
       → REQUIRES_NEW: only A rolls back (B already committed independently)
```

---

## 5. Isolation Levels

```java
@Transactional(isolation = Isolation.READ_COMMITTED)  // PostgreSQL default
public void transfer(...) { ... }
```

| Level | Dirty Read | Non-Repeatable Read | Phantom Read | Performance |
|-------|-----------|-------------------|-------------|-------------|
| **READ_UNCOMMITTED** | ✅ Possible | ✅ Possible | ✅ Possible | Fastest |
| **READ_COMMITTED** ✅ | ❌ Prevented | ✅ Possible | ✅ Possible | Fast |
| **REPEATABLE_READ** | ❌ Prevented | ❌ Prevented | ✅ Possible | Medium |
| **SERIALIZABLE** | ❌ Prevented | ❌ Prevented | ❌ Prevented | Slowest |

**PostgreSQL default**: `READ_COMMITTED` — a statement sees only data committed before the statement began. In practice, PostgreSQL's MVCC implementation prevents most anomalies even at this level.

---

## 6. Rollback Rules

```java
// Default behavior
@Transactional
public void method() {
    throw new RuntimeException();    // → ROLLBACK ✅
    throw new BusinessException();   // → ROLLBACK ✅ (extends RuntimeException)
    throw new IOException();         // → COMMIT ❌ (checked exception!)
}

// Custom rollback rules
@Transactional(rollbackFor = Exception.class)      // Roll back on ALL exceptions
@Transactional(noRollbackFor = BusinessException.class)  // Don't roll back on business errors

// Our saga orchestrator
@Transactional(noRollbackFor = {BusinessException.class, ResourceNotFoundException.class})
public SagaInstance executeStep(...) {
    // Business failures are recorded, not rolled back
}
```

### Rollback Decision Tree

```
Exception thrown from @Transactional method?
  │
  ├── RuntimeException (unchecked)
  │     └── rollback by default
  │           └── unless listed in noRollbackFor → COMMIT
  │
  └── Checked Exception
        └── COMMIT by default
              └── unless listed in rollbackFor → ROLLBACK
```

---

## 7. Common Pitfalls

### Pitfall 1: Self-Invocation

```java
@Service
public class MyService {
    public void methodA() {
        methodB();  // ❌ Direct call — bypasses proxy, no TX!
    }
    
    @Transactional
    public void methodB() { ... }
}

// Fix 1: Inject self
@Autowired private MyService self;
public void methodA() { self.methodB(); }  // Goes through proxy

// Fix 2: Extract to another service
```

### Pitfall 2: Checked Exceptions Don't Rollback

```java
@Transactional
public void process() throws IOException {
    walletRepository.save(wallet);
    if (error) throw new IOException("fail");
    // → Transaction COMMITS, wallet is saved, despite exception!
}

// Fix:
@Transactional(rollbackFor = IOException.class)
public void process() throws IOException { ... }
```

### Pitfall 3: Long-Running Transactions

```java
// ❌ BAD: Transaction holds DB connection for entire method
@Transactional
public void processReport() {
    List<Data> data = repository.findAll();       // DB call
    processHeavyComputation(data);                // 30 seconds of CPU work
    repository.saveAll(results);                  // DB call
}

// ✅ GOOD: Minimize time in transaction
public void processReport() {
    List<Data> data = repository.findAll();       // Short TX (or readOnly)
    List<Result> results = processComputation(data);  // No TX
    saveResults(results);                         // Short TX
}
```

### Pitfall 4: @Transactional on Private Method

```java
// ❌ BAD: @Transactional is IGNORED on private methods
@Transactional
private void saveData() { ... }  // Spring AOP can't proxy private methods

// ✅ GOOD: Must be public (or protected)
@Transactional
public void saveData() { ... }
```

---

## 8. TransactionTemplate vs @Transactional

| Feature | `@Transactional` | `TransactionTemplate` |
|---------|-----------------|----------------------|
| Style | Declarative (annotation) | Programmatic (code) |
| Granularity | Entire method | Specific code block |
| Flexibility | Limited (noRollbackFor, propagation) | Full control (manual rollback) |
| Self-invocation | ❌ Broken (proxy bypass) | ✅ Works everywhere |
| Nesting | Via propagation attributes | Manual nesting |
| Readability | ✅ Clean, minimal code | ❌ More verbose |
| Testing | Mock the proxy | Easy to unit test |

---

## 9. Interview Quick-Fire

**Q: Why does @Transactional not work on self-invocation?**
A: Spring uses AOP proxies to implement `@Transactional`. When a method calls another method in the same class, it bypasses the proxy and calls the target directly. The proxy never intercepts, so no transaction is started.

**Q: Why don't checked exceptions trigger rollback by default?**
A: Spring follows the EJB convention — checked exceptions represent recoverable conditions (like validation failures that can be retried), while unchecked exceptions represent programming errors. Use `rollbackFor = Exception.class` to change this.

**Q: When would you use TransactionTemplate over @Transactional?**
A: When you need transaction boundaries around a specific code block within a method (not the entire method), when dealing with self-invocation issues, or when you need to mix transactional and non-transactional code in one method — like our saga initialization.

**Q: What's the difference between REQUIRED and REQUIRES_NEW propagation?**
A: REQUIRED joins the existing transaction (or creates one if none exists) — caller and callee share the same TX. REQUIRES_NEW always creates a new independent transaction, suspending the existing one — callee's commit/rollback is independent of the caller.

**Q: How does readOnly=true help performance?**
A: Hibernate skips dirty-checking at flush time (no change detection), JDBC driver may enable read-replica routing, and PostgreSQL may use a lighter transaction snapshot mode.

---

## Key Takeaway

> We use **declarative `@Transactional`** for most service methods (each write operation is atomic) and **programmatic `TransactionTemplate`** when we need precise control (saga initialization). **`noRollbackFor`** is critical in the saga orchestrator — business failures must be recorded, not rolled back. Understanding proxy-based transactions, propagation, and rollback rules is essential for correct Spring transaction management.
