# Compensating Transactions

## Quick Summary

A **compensating transaction** is a transaction that **semantically undoes** the effect of a previously committed transaction. Unlike a database rollback (which prevents a commit), compensation happens **after** the original transaction has already been committed.

---

## 1. Rollback vs Compensation

```
ROLLBACK (within a single transaction):
  BEGIN;
    UPDATE wallet SET balance = 750 WHERE id = 1;  -- Debit $250
    -- Something goes wrong!
  ROLLBACK;  -- Database undoes the change; it never happened
  -- balance is still $1000 ✅

COMPENSATION (after transaction committed):
  Transaction 1: UPDATE wallet SET balance = 750 WHERE id = 1;  COMMIT;  -- Done
  -- Something goes wrong later in the saga!
  Transaction 2: UPDATE wallet SET balance = 1000 WHERE id = 1; COMMIT;  -- Compensate
  -- balance restored to $1000 ✅

Key difference: Rollback = undo uncommitted work
                Compensation = new commit that reverses previous commit
```

### Why Can't We Just Rollback?

In a distributed saga, each step is a **separate, committed transaction** on potentially different databases:

```
Step 1: BEGIN → Debit $250 from Wallet A (Shard 1) → COMMIT  ← Already committed!
Step 2: BEGIN → Credit $250 to Wallet B (Shard 2) → FAILS
                                                       │
                                                       ▼
Can't rollback Step 1 — it's committed on Shard 1!
Must run a NEW transaction to compensate: Credit $250 BACK to Wallet A
```

---

## 2. Compensation in Our Wallet Project

### Step-by-Step Compensation Map

| Step | Forward Action | Compensating Action |
|------|---------------|-------------------|
| `DEBIT_SOURCE_WALLET` | `wallet.debit($250)` | `wallet.credit($250)` (refund) |
| `CREDIT_DESTINATION_WALLET` | `wallet.credit($250)` | `wallet.debit($250)` (take back) |
| `UPDATE_TRANSACTION_STATUS` | `tx.status = SUCCESS` | `tx.status = previousStatus` (revert) |

### DebitSourceWalletStep — Forward + Compensation

```java
@Service
public class DebitSourceWalletStep implements SagaStepInterface {

    @Override @Transactional
    public boolean execute(SagaContext context) throws Exception {
        BigDecimal amount = new BigDecimal(context.getData().get("amount").toString());
        Long srcId = Long.valueOf(context.getData().get("sourceWalletId").toString());

        Wallet wallet = walletRepository.findById(srcId).orElseThrow();

        // Save state BEFORE for compensation
        context.put("fromWalletBalanceBeforeDebit", wallet.getBalance());

        wallet.debit(amount);       // Forward action
        walletRepository.save(wallet);

        context.put("fromWalletBalanceAfterDebit", wallet.getBalance());
        return true;
    }

    @Override @Transactional
    public boolean compensate(SagaContext context) throws Exception {
        BigDecimal amount = new BigDecimal(context.getData().get("amount").toString());
        Long srcId = Long.valueOf(context.getData().get("sourceWalletId").toString());

        Wallet wallet = walletRepository.findById(srcId).orElseThrow();
        wallet.credit(amount);      // Reverse action: credit back the debit
        walletRepository.save(wallet);
        return true;
    }
}
```

---

## 3. LIFO Compensation Order

Compensations execute in **reverse order** (Last-In-First-Out):

```
Forward execution:   Step 1 → Step 2 → Step 3 (fails)
Compensation:        Step 2 → Step 1  (reverse of completed steps)
                     Step 3 is not compensated (it never completed)

Why reverse order?
  Step 2 may depend on Step 1's effects.
  If we compensate Step 1 first, Step 2's compensation might fail
  because it references data that Step 1's compensation already undid.
```

### Code

```java
// SagaOrchestratorImpl.java
@Override @Transactional
public void compensateSaga(Long sagaInstanceId) {
    SagaInstance si = sagaInstanceRepository.findById(sagaInstanceId).orElseThrow();

    // Only compensate COMPLETED steps (not FAILED or PENDING)
    List<SagaStep> completed = sagaStepRepository
        .findBySagaInstanceIdAndStatus(sagaInstanceId, StepStatus.COMPLETED);

    if (completed.isEmpty()) {
        si.setStatus(SagaStatus.COMPENSATED);
        sagaInstanceRepository.save(si);
        return;
    }

    si.setStatus(SagaStatus.COMPENSATING);
    sagaInstanceRepository.save(si);

    Collections.reverse(completed);  // ← LIFO order

    boolean allOk = true;
    for (SagaStep step : completed) {
        if (!compensateStep(sagaInstanceId, step.getStepName())) {
            allOk = false;
            break;  // Stop compensating if one fails
        }
    }

    if (allOk) {
        si.setStatus(SagaStatus.COMPENSATED);
    } else {
        si.setStatus(SagaStatus.FAILED);  // Compensation itself failed!
    }
    sagaInstanceRepository.save(si);
}
```

---

## 4. Failure Scenarios

### Scenario 1: Step 2 Fails → Compensate Step 1

```
Step 1: DEBIT_SOURCE_WALLET → ✅ COMPLETED
  Wallet A: $1000 → $750

Step 2: CREDIT_DESTINATION_WALLET → ❌ FAILED
  (e.g., destination wallet is inactive)

Compensation:
  Compensate Step 1: CREDIT $250 back to Wallet A
  Wallet A: $750 → $1000 ✅

Transaction.status = FAILED
SagaInstance.status = COMPENSATED
```

### Scenario 2: Step 3 Fails → Compensate Steps 2, 1

```
Step 1: DEBIT_SOURCE_WALLET → ✅ COMPLETED
  Wallet A: $1000 → $750
Step 2: CREDIT_DESTINATION_WALLET → ✅ COMPLETED
  Wallet B: $500 → $750
Step 3: UPDATE_TRANSACTION_STATUS → ❌ FAILED

Compensation (reverse order):
  Compensate Step 2: DEBIT $250 from Wallet B → $750 → $500 ✅
  Compensate Step 1: CREDIT $250 to Wallet A → $750 → $1000 ✅

Transaction.status = FAILED
SagaInstance.status = COMPENSATED
```

### Scenario 3: Compensation Itself Fails

```
Step 1: DEBIT_SOURCE_WALLET → ✅ COMPLETED
Step 2: CREDIT_DESTINATION_WALLET → ❌ FAILED

Compensation:
  Compensate Step 1 → ❌ ALSO FAILS (e.g., database down)

SagaInstance.status = FAILED (neither completed nor compensated)
→ Requires manual intervention or retry scheduler
```

---

## 5. Design Rules for Compensating Transactions

### Rule 1: Semantic Reversal, Not Exact Undo

```
❌ Compensation should NOT just restore the exact previous value
   Why? Another transaction may have modified the balance between forward and compensate

Forward: balance = $1000 → debit $250 → balance = $750
Meanwhile: another transfer adds $100 → balance = $850
Compensate: balance = $850 → credit $250 → balance = $1100 ✅
  (NOT: SET balance = $1000 — that would lose the $100 addition!)
```

Our code does this correctly:
```java
// Compensate debit by doing a CREDIT (not by setting old value)
wallet.credit(amount);  // ✅ Relative operation, not absolute

// NOT this:
// wallet.setBalance(oldBalance);  // ❌ Overwrites concurrent changes
```

### Rule 2: Compensation Must Be Idempotent

Running compensation twice should produce the same result:

```
Compensate Step 1: credit $250 to Wallet A
  → balance: $750 → $1000

If compensation runs again (retry):
  → balance: $1000 → $1250  ← WRONG! Overcredited!

Solution: Check step status before compensating
  if (sagaStep.status == COMPLETED) → compensate
  if (sagaStep.status == COMPENSATED) → skip (already done)
```

Our code handles this:
```java
SagaStep sagaStep = sagaStepRepository
    .findBySagaInstanceIdAndStatusAndStepName(
        sagaInstanceId, StepStatus.COMPLETED, stepName)
    .orElseThrow();  // Only finds COMPLETED steps
```

### Rule 3: Save State for Compensation

```java
// In execute():
context.put("fromWalletBalanceBeforeDebit", wallet.getBalance());
context.put("transactionStatusBefore", tx.getStatus());

// In compensate():
TransactionStatus prev = TransactionStatus.valueOf(
    context.getData().get("transactionStatusBefore").toString()
);
tx.setStatus(prev);
```

The `SagaContext` (stored as JSONB in the database) preserves all data needed for compensation.

### Rule 4: Not All Operations Are Compensable

```
✅ Compensable: Financial operations (debit ↔ credit)
✅ Compensable: Status changes (SUCCESS → PENDING)
❌ Not easily compensable: Sending an email
❌ Not easily compensable: Calling external APIs
❌ Not easily compensable: Physical actions (ship a package)

For non-compensable operations:
  - Make them the LAST step (pivot transaction)
  - Or use a "pending" → "confirmed" pattern
  - Or accept that compensation means "send cancellation email"
```

---

## 6. Compensation vs Other Undo Mechanisms

| Mechanism | When | Scope | Data Loss |
|-----------|------|-------|-----------|
| **DB Rollback** | Before commit | Single transaction | None (as if never happened) |
| **Compensation** | After commit | Saga-level | Intermediate states visible |
| **Event Sourcing Revert** | Anytime | Event stream | None (events immutable, add reverse event) |
| **Point-in-time Recovery** | After disaster | Entire database | Lose changes after recovery point |
| **Soft Delete + Undo** | User-facing | Application level | None (just flip flag) |

---

## 7. Compensation in the Database

Each compensation is tracked in the `saga_step` table:

```
saga_step table after successful compensation:
┌────┬──────────────────┬────────────┬──────────────┐
│ id │ step_name        │ step_order │ status       │
├────┼──────────────────┼────────────┼──────────────┤
│ 1  │ DEBIT_SOURCE     │ 1          │ COMPENSATED  │ ← Was COMPLETED, now compensated
│ 2  │ CREDIT_DEST      │ 2          │ FAILED       │ ← This is what triggered compensation
│ 3  │ UPDATE_STATUS    │ 3          │ PENDING      │ ← Never executed
└────┴──────────────────┴────────────┴──────────────┘
```

---

## 8. Interview Quick-Fire

**Q: What is a compensating transaction?**
A: A new transaction that semantically undoes the effect of a previously committed transaction. It's the saga equivalent of a rollback, but for already-committed work.

**Q: Why can't you just rollback instead of compensate?**
A: Because in a saga, each step's transaction is already committed. You can't rollback a committed transaction — you must run a new transaction that reverses the effect.

**Q: What if compensation fails?**
A: The saga enters a FAILED state. This requires either automatic retry (our system retries with backoff) or manual intervention by an operator.

**Q: Are compensations always exact inverses?**
A: No. They're semantic reverses. A debit is compensated by a credit (relative operation), not by restoring the old balance (absolute operation). This preserves concurrent changes by other transactions.

**Q: What is a "pivot transaction" in saga terms?**
A: The point-of-no-return step. Before the pivot, the saga can be compensated backward. At/after the pivot, the saga must go forward (retry if needed). For us, the final status update is the pivot.

---

## Key Takeaway

> Compensating transactions are the **safety net** of the Saga pattern. Each step saves enough context before execution so that it can be reversed later. Compensation runs in **reverse order** (LIFO), uses **relative operations** (not absolute state restoration), and must be **idempotent**. Our system tracks every step's status in the database, ensuring compensation is reliable and auditable.
