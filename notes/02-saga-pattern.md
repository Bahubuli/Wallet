# Saga Pattern — Deep Dive

## Quick Summary

The **Saga Pattern** is a design pattern for managing **distributed transactions** without using a global lock. It breaks a long-lived transaction into a sequence of **local transactions**, each with a **compensating action** to undo its effects if a later step fails.

---

## 1. The Problem: Distributed Transactions

In a monolithic database, you can do:

```sql
BEGIN;
  UPDATE wallet SET balance = balance - 250 WHERE id = 1;  -- Debit
  UPDATE wallet SET balance = balance + 250 WHERE id = 2;  -- Credit
COMMIT;  -- Both succeed or both fail — ACID guaranteed
```

But when wallets **live on different database shards** (or different microservices):

```
┌──────────────────┐              ┌──────────────────┐
│   Shard 1        │              │   Shard 2        │
│   Wallet A       │              │   Wallet B       │
│   (PostgreSQL)   │              │   (PostgreSQL)   │
└──────────────────┘              └──────────────────┘

❌ Cannot wrap both in a single BEGIN...COMMIT
❌ No shared transaction manager
❌ If debit succeeds but credit fails → INCONSISTENCY
```

**The Saga Pattern solves this.**

---

## 2. Two Flavors of Saga

### 2a. Saga Choreography (Event-Driven)

Each service **publishes events** and **listens to events** from other services. No central coordinator.

```
┌──────────┐   WalletDebited   ┌──────────┐   WalletCredited   ┌──────────┐
│  Wallet  │ ────────────────► │  Wallet  │ ────────────────►  │ Txn Svc  │
│  Svc A   │                   │  Svc B   │                    │          │
│  (Debit) │ ◄──────────────── │ (Credit) │                    │ (Update) │
│          │   CreditFailed    │          │                    │          │
└──────────┘   (compensate)    └──────────┘                    └──────────┘
```

**Pros:**
- Loosely coupled — no single point of failure
- Each service is autonomous
- Good for simple, linear workflows

**Cons:**
- Hard to track overall progress (distributed state)
- Circular dependencies possible
- Difficult to debug (follow events across services)
- No single place to see the "big picture"
- Compensation logic scattered across services

### 2b. Saga Orchestration (Central Coordinator) ← **Our Approach**

A **central orchestrator** tells each service what to do and when. It tracks the saga state.

```
                    ┌─────────────────────────┐
                    │   Saga Orchestrator      │
                    │   (SagaOrchestratorImpl) │
                    │                         │
                    │  State Machine:          │
                    │  STARTED → RUNNING →     │
                    │  COMPLETED / COMPENSATED │
                    └────────┬────────────────┘
                             │
              ┌──────────────┼──────────────┐
              │              │              │
              ▼              ▼              ▼
        ┌──────────┐  ┌──────────┐  ┌──────────┐
        │  Step 1  │  │  Step 2  │  │  Step 3  │
        │  Debit   │  │  Credit  │  │  Update  │
        │  Source  │  │  Dest    │  │  Status  │
        └──────────┘  └──────────┘  └──────────┘
```

**Pros:**
- Centralized state tracking — easy to monitor and debug
- Clear execution order
- Single place for compensation logic
- Easy to add new steps
- Transaction state persisted in DB

**Cons:**
- Orchestrator is a single point of complexity (not failure — it's stateless)
- Tighter coupling between orchestrator and steps
- More upfront design effort

### Comparison Table

| Aspect | Choreography | Orchestration |
|--------|-------------|---------------|
| **Coordination** | Implicit (events) | Explicit (orchestrator) |
| **Coupling** | Loose | Steps coupled to orchestrator |
| **Visibility** | Hard to trace | Central state tracking |
| **Debugging** | Difficult (distributed) | Easy (single state machine) |
| **Compensation** | Each service handles own | Centralized reverse-order |
| **Complexity** | Grows with participants | Contained in orchestrator |
| **Best for** | Simple flows, few steps | Complex flows, many steps |

---

## 3. Our Implementation Architecture

### Component Map

```
TransactionController
    │
    ▼
TransactionService.createTransaction()
    │
    ▼
TransferSagaService.initiateTransfer()    ← Saga Coordinator
    │
    ├─ 1. Create Transaction (PENDING) + Start Saga (STARTED)
    │     └─ Atomic via TransactionTemplate
    │
    ├─ 2. Execute Steps via SagaOrchestrator
    │     ├─ Step 1: DEBIT_SOURCE_WALLET
    │     ├─ Step 2: CREDIT_DESTINATION_WALLET
    │     └─ Step 3: UPDATE_TRANSACTION_STATUS
    │
    └─ 3. Complete Saga (COMPLETED) or Compensate (COMPENSATED)
```

### Step Interface Contract

Every saga step must implement:

```java
public interface SagaStepInterface {
    boolean execute(SagaContext context) throws Exception;     // Forward action
    boolean compensate(SagaContext context) throws Exception;  // Reverse action
    String getStepName();                                      // Unique identifier
    Integer getStepOrder();                                    // Execution sequence

    // Optional hooks with defaults
    default String getCompensationAction() { return "compensate_" + getStepName(); }
    default Integer getMaxRetries() { return 3; }
    default boolean validate(SagaContext context) { return true; }
    default void onSuccess(SagaContext context) {}
    default void onFailure(SagaContext context, Exception error) {}
}
```

### Step Factory — Registry Pattern

```java
@Component
public class SagaStepFactory {

    public enum SagaStepType {
        DEBIT_SOURCE_WALLET,
        CREDIT_DESTINATION_WALLET,
        UPDATE_TRANSACTION_STATUS
    }

    public enum SagaType {
        TRANSACTION_TRANSFER(List.of(
            SagaStepType.DEBIT_SOURCE_WALLET,
            SagaStepType.CREDIT_DESTINATION_WALLET,
            SagaStepType.UPDATE_TRANSACTION_STATUS
        ));
        // Easily extensible: add DEPOSIT, WITHDRAWAL saga types
    }

    // Maps step types to Spring-managed implementations
    private final Map<SagaStepType, SagaStepInterface> stepMap;
}
```

---

## 4. Saga State Machine

### Saga Instance States

```
                    ┌─────────┐
                    │ STARTED │
                    └────┬────┘
                         │ first step begins
                         ▼
                    ┌─────────┐
              ┌─────│ RUNNING │─────┐
              │     └─────────┘     │
              │ all steps pass      │ any step fails
              ▼                     ▼
        ┌───────────┐       ┌──────────────┐
        │ COMPLETED │       │ COMPENSATING │
        └───────────┘       └──────┬───────┘
                                   │
                    ┌──────────────┼──────────────┐
                    │ all compensations OK         │ compensation fails
                    ▼                              ▼
             ┌─────────────┐              ┌────────┐
             │ COMPENSATED │              │ FAILED │
             └─────────────┘              └────────┘
```

### Step States

```
        ┌─────────┐
        │ PENDING │
        └────┬────┘
             │ step starts
             ▼
        ┌─────────┐
   ┌────│ RUNNING │────┐
   │    └─────────┘    │
   │ success           │ failure
   ▼                   ▼
┌───────────┐    ┌────────┐
│ COMPLETED │    │ FAILED │
└─────┬─────┘    └────────┘
      │ compensation triggered
      ▼
┌──────────────┐
│ COMPENSATING │
└──────┬───────┘
       │
  ┌────┴────┐
  │         │
  ▼         ▼
┌─────────────┐  ┌────────┐
│ COMPENSATED │  │ FAILED │
└─────────────┘  └────────┘

Special: SKIPPED — step was never executed (saga failed before reaching it)
```

---

## 5. Execution Flow — Happy Path

```
Step-by-step execution for a $250 transfer from Wallet A → Wallet B:

┌──────────────────────────────────────────────────────────────┐
│ 1. INITIALIZATION (Atomic via TransactionTemplate)           │
│    • Create Transaction: PENDING, sagaInstanceId = -1       │
│    • Create SagaContext with sourceWalletId, destWalletId,  │
│      amount, transactionId                                   │
│    • Start Saga → SagaInstance(STARTED)                     │
│    • Link: Transaction.sagaInstanceId = saga.id             │
└──────────────────────────────┬───────────────────────────────┘
                               │
┌──────────────────────────────▼───────────────────────────────┐
│ 2. DEBIT_SOURCE_WALLET (Step 1)                              │
│    • Read Wallet A (balance = $1000)                        │
│    • context.put("fromWalletBalanceBeforeDebit", $1000)     │
│    • Validate: hasSufficientBalance($250) → true            │
│    • wallet.debit($250) → balance = $750                    │
│    • Save wallet                                            │
│    • context.put("fromWalletBalanceAfterDebit", $750)       │
│    • SagaStep → COMPLETED                                   │
└──────────────────────────────┬───────────────────────────────┘
                               │
┌──────────────────────────────▼───────────────────────────────┐
│ 3. CREDIT_DESTINATION_WALLET (Step 2)                        │
│    • Read Wallet B (balance = $500)                         │
│    • context.put("toWalletBalanceBeforeCredit", $500)       │
│    • wallet.credit($250) → balance = $750                   │
│    • Save wallet                                            │
│    • context.put("toWalletBalanceAfterCredit", $750)        │
│    • SagaStep → COMPLETED                                   │
└──────────────────────────────┬───────────────────────────────┘
                               │
┌──────────────────────────────▼───────────────────────────────┐
│ 4. UPDATE_TRANSACTION_STATUS (Step 3)                        │
│    • Read Transaction                                        │
│    • context.put("transactionStatusBefore", PENDING)        │
│    • Transaction.status = SUCCESS                            │
│    • Save transaction                                        │
│    • context.put("transactionStatusAfter", SUCCESS)         │
│    • SagaStep → COMPLETED                                   │
└──────────────────────────────┬───────────────────────────────┘
                               │
┌──────────────────────────────▼───────────────────────────────┐
│ 5. COMPLETE SAGA                                             │
│    • SagaInstance.status = COMPLETED                         │
│    • SagaInstance.completedDate = now                        │
└──────────────────────────────────────────────────────────────┘
```

---

## 6. Execution Flow — Failure + Compensation

```
What happens if Step 2 (Credit Destination) fails:

Step 1: DEBIT_SOURCE_WALLET → ✅ COMPLETED  (Wallet A: $1000 → $750)
Step 2: CREDIT_DESTINATION_WALLET → ❌ FAILED (e.g., destination wallet inactive)
Step 3: UPDATE_TRANSACTION_STATUS → SKIPPED (never executed)

COMPENSATION BEGINS (LIFO — Last In, First Out):
──────────────────────────────────────────────

Saga.status = COMPENSATING

Compensate Step 2: No action needed (step FAILED, not COMPLETED)
Compensate Step 1: DEBIT_SOURCE_WALLET.compensate()
    • Read Wallet A (balance = $750)
    • wallet.credit($250) → balance = $1000  (money returned!)
    • Save wallet
    • SagaStep.status = COMPENSATED

Transaction.status = FAILED
Saga.status = COMPENSATED
```

### LIFO Compensation Order — Why?

```
Execute:     Step 1 → Step 2 → Step 3
Compensate:  Step 3 → Step 2 → Step 1  (REVERSE order)
```

Why reverse? Because **later steps may depend on earlier steps**. If Step 2 credited money that Step 1 debited, you must undo Step 2 before Step 1, otherwise you'd be undoing the debit while the credit still references it.

Code:
```java
// SagaOrchestratorImpl.java
public void compensateSaga(Long sagaInstanceId) {
    List<SagaStep> completed = sagaStepRepository
        .findBySagaInstanceIdAndStatus(sagaInstanceId, StepStatus.COMPLETED);
    Collections.reverse(completed);  // ← LIFO order
    for (SagaStep s : completed) {
        compensateStep(sagaInstanceId, s.getStepName());
    }
}
```

---

## 7. SagaContext — Data Passing Between Steps

The `SagaContext` is a **shared data container** serialized as JSON in the `saga_instance.context` column (PostgreSQL JSONB).

```java
SagaContext context = SagaContext.builder()
    .sagaType("TRANSACTION_TRANSFER")
    .data(Map.of(
        "sourceWalletId", 123L,
        "destinationWalletId", 456L,
        "amount", BigDecimal.valueOf(250),
        "transactionId", 789L
    ))
    .build();
```

### Data Flow Through Steps

```
Step 1 (Debit):
    READS:  context.get("sourceWalletId")
            context.get("amount")
    WRITES: context.put("fromWalletBalanceBeforeDebit", ...)
            context.put("fromWalletBalanceAfterDebit", ...)

Step 2 (Credit):
    READS:  context.get("destinationWalletId")
            context.get("amount")
    WRITES: context.put("toWalletBalanceBeforeCredit", ...)
            context.put("toWalletBalanceAfterCredit", ...)

Step 3 (Update Status):
    READS:  context.get("transactionId")
            context.get("newStatus")
    WRITES: context.put("transactionStatusBefore", ...)
            context.put("transactionStatusAfter", ...)
```

After each step, the context is **serialized back to the database**:
```java
si.setContext(objectMapper.writeValueAsString(ctx));
sagaInstanceRepository.save(si);
```

This means if the server crashes, the next restart can **resume from the last persisted context**.

---

## 8. Persistence Model

### Database Tables

```
saga_instance                               saga_step
┌────────────────────────┐                  ┌─────────────────────────┐
│ id (PK)                │ 1            * │ id (PK)                  │
│ saga_type              │◄──────────────│ saga_instance_id (FK)     │
│ status                 │                │ step_order (unique/saga)  │
│ context (JSONB)        │                │ step_name                 │
│ current_step           │                │ status                    │
│ error_details          │                │ error_message             │
│ retry_count            │                │ retry_count               │
│ max_retries            │                │ max_retries               │
│ version (@Version)     │                │ step_data (JSONB)         │
│ created_date           │                │ version (@Version)        │
│ updated_date           │                │ created_date              │
│ completed_date         │                │ started_date              │
│ expiry_time            │                │ completed_date            │
└────────────────────────┘                └─────────────────────────┘
```

---

## 9. Adding a New Saga Step (Extensibility)

To add a new step (e.g., `NOTIFY_USER`):

```java
// 1. Create the step class
@Service
public class NotifyUserStep implements SagaStepInterface {
    @Override
    public boolean execute(SagaContext ctx) { /* send notification */ return true; }
    @Override
    public boolean compensate(SagaContext ctx) { /* no-op or mark unsent */ return true; }
    @Override
    public String getStepName() { return "NOTIFY_USER"; }
    @Override
    public Integer getStepOrder() { return 4; }
}

// 2. Add to SagaStepType enum
public enum SagaStepType {
    DEBIT_SOURCE_WALLET,
    CREDIT_DESTINATION_WALLET,
    UPDATE_TRANSACTION_STATUS,
    NOTIFY_USER  // ← new
}

// 3. Add to SagaType step list
TRANSACTION_TRANSFER(List.of(
    SagaStepType.DEBIT_SOURCE_WALLET,
    SagaStepType.CREDIT_DESTINATION_WALLET,
    SagaStepType.UPDATE_TRANSACTION_STATUS,
    SagaStepType.NOTIFY_USER  // ← new
));

// 4. Register in SagaStepFactory constructor
public SagaStepFactory(... NotifyUserStep notify) {
    this.stepMap = Map.of(
        ...,
        SagaStepType.NOTIFY_USER, notify
    );
}
```

---

## 10. Interview Quick-Fire

**Q: What's the difference between Saga and distributed transactions?**
A: Saga gives up **atomicity** (uses eventual consistency) in exchange for **availability** and **partition tolerance**. Distributed transactions (2PC) maintain atomicity but sacrifice availability.

**Q: Can a saga guarantee isolation?**
A: No. Between saga steps, **intermediate states are visible** to other transactions. This is called a "dirty read" problem. Solutions: countermeasures like semantic locks, commutative updates, or pessimistic views.

**Q: What if the orchestrator crashes mid-saga?**
A: Since saga state is persisted in the database, upon restart, a recovery process can find sagas in `RUNNING` or `COMPENSATING` state and resume them.

**Q: What is a "pivot transaction" in a saga?**
A: The point of no return. Before the pivot, the saga can be compensated. At the pivot, the decision is made to commit. After the pivot, the saga must go forward (retry, not compensate). In our project, `UPDATE_TRANSACTION_STATUS` (Step 3) is the pivot.

---

## Key Takeaway

> We use **Saga Orchestration** because our transfer spans multiple database shards. The orchestrator provides a **centralized state machine** with persistent tracking, automatic **LIFO compensation**, and per-step **retry with exponential backoff**. Every step can be independently executed and compensated, making the system eventually consistent even across shard boundaries.
