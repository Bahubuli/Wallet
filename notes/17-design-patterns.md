# Design Patterns Used in This Project

## Quick Summary

This project uses at least **10 design patterns** across different layers. This note catalogs each one with its purpose, implementation, and trade-offs.

---

## 1. Strategy Pattern

**Intent**: Define a family of algorithms, encapsulate each one, and make them interchangeable at runtime.

```
                  ┌──────────────────────┐
                  │  SagaStepInterface   │  ← Common contract
                  │  + execute(context)  │
                  │  + compensate(ctx)   │
                  └──────────┬───────────┘
                             │
              ┌──────────────┼──────────────┐
              ▼              ▼              ▼
    ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐
    │ DebitSource   │ │ CreditDest   │ │ UpdateTxStatus   │
    │ WalletStep   │ │ WalletStep   │ │ Step             │
    └──────────────┘ └──────────────┘ └──────────────────┘
```

```java
// Strategy interface
public interface SagaStepInterface {
    void execute(Map<String, Object> context);
    void compensate(Map<String, Object> context);
}

// Concrete strategies
@Component
public class DebitSourceWalletStep implements SagaStepInterface {
    public void execute(Map<String, Object> context) {
        Long walletId = (Long) context.get("sourceWalletId");
        walletService.debitWallet(walletId, amount);
    }
    public void compensate(Map<String, Object> context) {
        walletService.creditWallet(walletId, amount);  // Reverse the debit
    }
}
```

**Where**: Saga step implementations — each step has the same interface but different behavior.
**Benefit**: Adding a new step type requires zero changes to the orchestrator.

---

## 2. Factory Pattern

**Intent**: Create objects without exposing creation logic. The factory decides which concrete class to instantiate.

```java
@Component
public class SagaStepFactory {
    private final DebitSourceWalletStep debitStep;
    private final CreditDestinationWalletStep creditStep;
    private final UpdateTransactionStatus updateTxStep;

    public SagaStepInterface createStep(SagaStepType stepType, SagaType sagaType) {
        return switch (sagaType) {
            case TRANSFER -> switch (stepType) {
                case DEBIT_SOURCE     -> debitStep;
                case CREDIT_DEST      -> creditStep;
                case UPDATE_TX_STATUS -> updateTxStep;
                default -> throw new IllegalArgumentException("Unknown step: " + stepType);
            };
            default -> throw new IllegalArgumentException("Unknown saga: " + sagaType);
        };
    }
}
```

**Where**: `SagaStepFactory` — maps `(SagaType, SagaStepType)` → concrete step implementation.
**Benefit**: Orchestrator doesn't know which step classes exist — it asks the factory.
**Extension**: Adding a DEPOSIT saga type only requires adding a new `case` in the factory.

---

## 3. Builder Pattern

**Intent**: Construct complex objects step by step, allowing different representations.

```java
// Lombok @Builder on entities
@Builder
public class SagaInstance {
    private Long id;
    private SagaType sagaType;
    private SagaStatus status;
    private Map<String, Object> context;
    private Integer currentStep;
    private Integer maxRetries;
}

// Usage
SagaInstance saga = SagaInstance.builder()
    .sagaType(SagaType.TRANSFER)
    .status(SagaStatus.STARTED)
    .context(Map.of("sourceWalletId", 1L, "amount", BigDecimal.TEN))
    .currentStep(0)
    .maxRetries(3)
    .retryCount(0)
    .build();
```

Also on DTOs:
```java
ErrorResponseDTO.builder()
    .status(400)
    .error("Validation Failed")
    .message("Amount must be positive")
    .timestamp(Instant.now())
    .build();
```

**Where**: `SagaInstance`, `SagaStep`, `ErrorResponseDTO`, and other complex objects.
**Benefit**: Readable construction of objects with many fields, immutable-friendly.

---

## 4. Template Method Pattern

**Intent**: Define the skeleton of an algorithm in a base class, letting subclasses override specific steps.

```java
// SagaOrchestratorImpl defines the template
public class SagaOrchestratorImpl implements SagaOrchestrator {

    // Template: the execution algorithm is fixed
    public SagaInstance executeSaga(SagaInstance saga) {
        List<SagaStep> steps = getSteps(saga);         // Step 1: Get steps
        for (SagaStep step : steps) {
            SagaStepInterface impl = factory.createStep(  // Step 2: Resolve impl
                step.getStepType(), saga.getSagaType());
            executeWithRetry(impl, step, saga);           // Step 3: Execute
        }
        return finalizeSaga(saga);                        // Step 4: Finalize
    }
    
    // The retry mechanism wraps execution
    private void executeWithRetry(SagaStepInterface impl, SagaStep step, SagaInstance saga) {
        retryTemplate.execute(ctx -> {
            impl.execute(saga.getContext());  // ← This varies per step (Strategy)
            return null;
        });
    }
}
```

**Where**: `SagaOrchestratorImpl.executeSaga()` — fixed algorithm skeleton, variable step implementations.
**Benefit**: Consistent execution flow (retry, error handling, status updates) applied to all saga types.

---

## 5. Repository Pattern

**Intent**: Encapsulate data access behind a collection-like interface — services don't know about SQL/JDBC.

```java
public interface WalletRepository extends JpaRepository<Wallet, Long> {
    List<Wallet> findByUserId(Long userId);
    Page<Wallet> findByUserId(Long userId, Pageable pageable);
}

// Service uses it like a collection
Wallet w = walletRepository.findById(id).orElseThrow(...);
walletRepository.save(w);
```

**Where**: All repositories (`WalletRepository`, `UserRepository`, `TransactionRepository`, `SagaInstanceRepository`, `SagaStepRepository`).
**Benefit**: Swapping PostgreSQL for MongoDB would only change repository implementations, not services.

---

## 6. Service Layer Pattern

**Intent**: Define an application's boundary with a layer of services that establishes available operations and coordinates responses.

```
Controller → Service → Repository
             ↑
             Business logic lives here
             Transaction management here
             DTO ↔ Entity mapping here
```

```java
@Service
public class WalletService {
    // Orchestrates business logic
    @Transactional
    public WalletResponseDTO createWallet(WalletRequestDTO req) {
        User user = userRepository.findById(req.getUserId())
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Wallet w = new Wallet();
        w.setUserId(req.getUserId());
        w.setBalance(req.getBalance());
        return mapToResponseDTO(walletRepository.save(w));
    }
}
```

**Where**: `WalletService`, `UserService`, `TransactionService`, `TransferSagaService`.
**Benefit**: Controllers stay thin (only HTTP concerns), services are reusable across controllers.

---

## 7. Facade Pattern

**Intent**: Provide a simplified interface to a complex subsystem.

```java
@Service
public class TransactionService {
    private final TransferSagaService transferSagaService;
    
    // Simple interface to a complex saga subsystem
    public TransactionResponseDTO createTransaction(TransactionRequestDTO req) {
        return switch (req.getType()) {
            case TRANSFER -> transferSagaService.initiateTransfer(req);
            // Future: case DEPOSIT -> depositService.initiate(req);
            default -> throw new BusinessException("Unsupported type");
        };
    }
}
```

```
Client sees:
  transactionService.createTransaction(req)  ← Simple

Behind the facade:
  TransferSagaService
    → TransactionTemplate (atomic init)
    → SagaOrchestratorImpl (step execution)
       → SagaStepFactory (step resolution)
          → DebitSourceWalletStep (debit)
          → CreditDestinationWalletStep (credit)
          → UpdateTransactionStatus (finalize)
       → RetryTemplate (retry logic)
    → SagaInstanceRepository (persistence)
    → SagaStepRepository (persistence)
```

**Where**: `TransactionService` facades the entire saga subsystem.
**Benefit**: Controllers and external callers don't need to know about sagas, steps, retries, or compensation.

---

## 8. Rich Domain Model (DDD-Lite)

**Intent**: Put business logic in the entity itself, not just in services.

```java
@Entity
public class Wallet {
    private BigDecimal balance;

    // Business methods on the entity
    public void debit(BigDecimal amount) {
        if (!hasSufficientBalance(amount))
            throw new BusinessException("Insufficient balance");
        this.balance = this.balance.subtract(amount);
    }

    public void credit(BigDecimal amount) {
        this.balance = this.balance.add(amount);
    }

    public boolean hasSufficientBalance(BigDecimal amount) {
        return this.balance.compareTo(amount) >= 0;
    }
}
```

**Contrast with Anemic Domain Model:**
```java
// ❌ Anemic — entity is just a data bag
public class Wallet {
    private BigDecimal balance;
    // Only getters/setters, no business methods
}

// ❌ Service does everything
public class WalletService {
    public void debit(Wallet w, BigDecimal amount) {
        if (w.getBalance().compareTo(amount) < 0) throw ...;
        w.setBalance(w.getBalance().subtract(amount));
    }
}
```

**Where**: `Wallet.debit()`, `Wallet.credit()`, `Wallet.hasSufficientBalance()`.
**Benefit**: Business rules can't be bypassed — any code with a `Wallet` reference gets validation automatically.

---

## 9. Observer/Event Pattern (via Saga State Machine)

**Intent**: An object changes state and notifies dependents.

```
SagaInstance State Transitions:
  STARTED → RUNNING → COMPLETED     (happy path)
  STARTED → RUNNING → COMPENSATING → COMPENSATED  (failure path)
  STARTED → RUNNING → COMPENSATING → FAILED       (compensation failure)

SagaStep State Transitions:
  PENDING → COMPLETED              (success)
  PENDING → FAILED → COMPENSATED   (failure + compensation)
  PENDING → SKIPPED                (previous step failed)
```

Each state transition triggers specific behavior in the orchestrator — analogous to an event-driven state machine.

---

## 10. Dependency Injection (IoC)

**Intent**: Objects receive their dependencies from an external source rather than creating them.

```java
@Service
public class TransferSagaService {
    // All dependencies injected via constructor
    private final TransactionRepository transactionRepository;
    private final SagaOrchestrator sagaOrchestrator;
    private final SagaStepFactory sagaStepFactory;
    private final TransactionTemplate transactionTemplate;
    
    public TransferSagaService(                      // Constructor injection
            TransactionRepository transactionRepository,
            SagaOrchestrator sagaOrchestrator,
            SagaStepFactory sagaStepFactory,
            PlatformTransactionManager tm) {
        this.transactionRepository = transactionRepository;
        this.sagaOrchestrator = sagaOrchestrator;
        this.sagaStepFactory = sagaStepFactory;
        this.transactionTemplate = new TransactionTemplate(tm);
    }
}
```

**Where**: Every `@Service`, `@Component`, `@RestController` in the project.
**Benefit**: Loose coupling, easy testing (inject mocks), managed lifecycle.

---

## Pattern Summary Table

| Pattern | Where Used | Purpose |
|---------|-----------|---------|
| **Strategy** | `SagaStepInterface` + implementations | Interchangeable saga step behaviors |
| **Factory** | `SagaStepFactory` | Step resolution by type |
| **Builder** | `SagaInstance`, `SagaStep`, `ErrorResponseDTO` | Complex object construction |
| **Template Method** | `SagaOrchestratorImpl.executeSaga()` | Fixed algorithm, variable steps |
| **Repository** | All `*Repository` interfaces | Data access abstraction |
| **Service Layer** | All `*Service` classes | Business logic orchestration |
| **Facade** | `TransactionService` | Simplified interface to saga subsystem |
| **Rich Domain** | `Wallet.debit()`, `credit()` | Entity-owned business logic |
| **State Machine** | Saga/Step status transitions | Event-driven state management |
| **DI/IoC** | All Spring-managed beans | Loose coupling via constructor injection |

---

## Interview Quick-Fire

**Q: Name 3 design patterns in this project and explain why each was chosen.**
A: (1) **Strategy** for saga steps — allows adding new step types without modifying the orchestrator. (2) **Factory** for step creation — decouples the orchestrator from concrete step classes. (3) **Template Method** in the orchestrator — provides consistent retry, error handling, and state management for all step types while allowing each step to have unique business logic.

**Q: What's the difference between Rich and Anemic Domain Models?**
A: Rich domain models embed business logic in entities (`Wallet.debit()` validates balance and throws). Anemic models keep entities as pure data holders with logic in services. Rich models prevent business rule bypass; anemic models centralize logic but risk duplication.

**Q: Why constructor injection over field injection (@Autowired)?**
A: Constructor injection makes dependencies explicit (visible in constructor), enables immutability (final fields), supports unit testing without Spring context (pass mocks directly), and fails fast if a dependency is missing (compile error, not runtime NPE).

---

## Key Takeaway

> This project demonstrates **10+ design patterns** working together: **Strategy + Factory** for extensible saga steps, **Template Method** for the execution algorithm, **Builder** for complex object creation, **Repository** for data access abstraction, **Service Layer + Facade** for clean architecture, **Rich Domain Model** for entity business logic, and **Dependency Injection** for loose coupling throughout.
