# Optimistic Locking vs Pessimistic Locking

## Quick Summary

| Aspect | Optimistic Locking | Pessimistic Locking |
|--------|-------------------|---------------------|
| **Philosophy** | "Conflicts are rare — detect them" | "Conflicts are likely — prevent them" |
| **Lock type** | No DB lock; version check at write time | Actual DB row/table lock acquired |
| **Concurrency** | High (no blocking) | Low (blocked threads wait) |
| **Conflict handling** | Retry on `OptimisticLockException` | Waiting + potential deadlocks |
| **Throughput** | Higher under low contention | Higher under high contention on same row |
| **Best for** | Read-heavy, low write contention | Write-heavy, high contention on same rows |

---

## 1. What is Optimistic Locking?

Optimistic Locking assumes that **multiple transactions can complete without affecting each other**. Instead of locking the row, it uses a **version number** (or timestamp) to detect conflicts at commit time.

### How It Works

```
1. Transaction A reads Wallet (version = 1, balance = 1000)
2. Transaction B reads Wallet (version = 1, balance = 1000)
3. Transaction A: UPDATE wallet SET balance = 900, version = 2 WHERE id = 1 AND version = 1
   → ✅ Success (1 row updated, version was still 1)
4. Transaction B: UPDATE wallet SET balance = 800, version = 2 WHERE id = 1 AND version = 1
   → ❌ Fails (0 rows updated — version is now 2, not 1)
   → Throws ObjectOptimisticLockingFailureException
```

```
Timeline:
──────────────────────────────────────────────────►

TxA reads (v=1)────────TxA writes (v=1→2) ✅
TxB reads (v=1)──────────────TxB writes (v=1→?) ❌ CONFLICT
```

### JPA Implementation (`@Version`)

```java
@Entity
@Table(name = "wallet")
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "balance", nullable = false)
    private BigDecimal balance;

    @Version  // ← This enables optimistic locking
    private Long version;
}
```

**What Hibernate generates:**

```sql
UPDATE wallet
SET balance = ?, version = version + 1
WHERE id = ? AND version = ?
--                          ↑ Only updates if version matches
```

If **zero rows** are updated → Hibernate throws `ObjectOptimisticLockingFailureException`.

### Where We Use It in This Project

| Entity | Why |
|--------|-----|
| `Wallet` | Prevents double-debits during concurrent transfers |
| `SagaInstance` | Prevents concurrent saga state changes |
| `SagaStep` | Prevents concurrent step status updates |

---

## 2. What is Pessimistic Locking?

Pessimistic Locking assumes that **conflicts are likely**, so it acquires a database lock **before** reading the data. Other transactions must wait until the lock is released.

### How It Works

```
1. Transaction A: SELECT * FROM wallet WHERE id = 1 FOR UPDATE;
   → Row is LOCKED. No other transaction can modify it.
2. Transaction B: SELECT * FROM wallet WHERE id = 1 FOR UPDATE;
   → BLOCKED — waits for Transaction A to finish.
3. Transaction A: UPDATE wallet SET balance = 900 WHERE id = 1;
4. Transaction A: COMMIT;
   → Lock released.
5. Transaction B: Now gets the row (with updated balance = 900)
6. Transaction B: UPDATE wallet SET balance = 800 WHERE id = 1;
7. Transaction B: COMMIT;
```

```
Timeline:
──────────────────────────────────────────────────────────────►

TxA LOCK────────READ────────WRITE────────COMMIT (unlock)
TxB ....BLOCKED..............................LOCK──READ──WRITE──COMMIT
```

### JPA Implementation (`@Lock`)

```java
@Repository
public interface WalletRepository extends JpaRepository<Wallet, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT w FROM Wallet w WHERE w.id = :id")
    Optional<Wallet> findByIdForUpdate(@Param("id") Long id);
}
```

**What Hibernate generates:**

```sql
SELECT * FROM wallet WHERE id = ? FOR UPDATE
```

### Lock Modes in JPA

| Lock Mode | SQL | Use Case |
|-----------|-----|----------|
| `PESSIMISTIC_READ` | `SELECT ... FOR SHARE` | Allow concurrent reads, block writes |
| `PESSIMISTIC_WRITE` | `SELECT ... FOR UPDATE` | Block both reads and writes |
| `PESSIMISTIC_FORCE_INCREMENT` | `SELECT ... FOR UPDATE` + version++ | Lock + bump version |

---

## 3. Deep Comparison

### Performance Under Different Contention Levels

```
Throughput
    ▲
    │  Optimistic ────────────╲
    │                          ╲
    │                           ╲──── Crossover point
    │                          ╱
    │  Pessimistic ──────────╱
    │
    └──────────────────────────────► Contention Level
       Low                    High
```

- **Low contention**: Optimistic wins (no lock overhead, no blocking)
- **High contention**: Pessimistic wins (no wasted retry work)
- **Crossover**: Depends on read/write ratio and conflict probability

### Detailed Trade-offs

| Criteria | Optimistic | Pessimistic |
|----------|-----------|-------------|
| **Lock overhead** | None (just version column) | Lock manager, memory, CPU |
| **Deadlocks** | Impossible | Possible (circular waits) |
| **Starvation** | Possible (always losing retry race) | Possible (low-priority waits) |
| **Wasted work** | Yes (failed tx rolled back) | No (but blocked time is wasted) |
| **Scalability** | Better (no lock contention) | Worse (lock contention bottleneck) |
| **Complexity** | Need retry logic | Need lock ordering to avoid deadlocks |
| **Connection holding** | Short (fail-fast) | Long (held during lock wait) |
| **DB connection pool** | Less pressure | More pressure (connections held longer) |

### When to Choose What

| Scenario | Choice | Why |
|----------|--------|-----|
| E-commerce product page views | Optimistic | 99% reads, rare writes |
| Wallet balance updates | Optimistic + Retry | Moderate write freq, retries handle conflicts |
| Inventory decrement (flash sale) | Pessimistic | Massive concurrent writes to same row |
| Bank wire transfer | Pessimistic | Cannot afford failed attempts |
| Distributed saga steps | Optimistic | Steps on different shards, low per-row contention |
| Counter/analytics increment | Pessimistic or `UPDATE SET x = x + 1` | Atomic increment pattern |

---

## 4. How This Project Handles Optimistic Lock Failures

### Retry with Exponential Backoff

When an `ObjectOptimisticLockingFailureException` occurs during a saga step, the `RetryTemplate` catches it and retries:

```java
// SagaOrchestratorImpl.java
private static final Map<Class<? extends Throwable>, Boolean> TRANSIENT_EXCEPTIONS;
static {
    TRANSIENT_EXCEPTIONS = new HashMap<>();
    TRANSIENT_EXCEPTIONS.put(ObjectOptimisticLockingFailureException.class, true);  // ← Optimistic lock failure
    TRANSIENT_EXCEPTIONS.put(CannotAcquireLockException.class, true);
    TRANSIENT_EXCEPTIONS.put(TransientDataAccessException.class, true);
}

private RetryTemplate buildRetryTemplate(int maxAttempts) {
    SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(maxAttempts, TRANSIENT_EXCEPTIONS, true);
    ExponentialBackOffPolicy backOff = new ExponentialBackOffPolicy();
    backOff.setInitialInterval(1_000L);    // 1 second
    backOff.setMultiplier(2.0);            // doubles each retry
    backOff.setMaxInterval(10_000L);       // capped at 10 seconds
    RetryTemplate rt = new RetryTemplate();
    rt.setRetryPolicy(retryPolicy);
    rt.setBackOffPolicy(backOff);
    return rt;
}
```

### Retry Flow

```
Attempt 1: Execute step
    ❌ ObjectOptimisticLockingFailureException
    Wait 1 second...
Attempt 2: Execute step (re-reads fresh version)
    ❌ ObjectOptimisticLockingFailureException
    Wait 2 seconds...
Attempt 3: Execute step (re-reads fresh version)
    ✅ Success — version matched this time
```

---

## 5. Common Pitfalls

### Pitfall 1: Forgetting to re-read after optimistic lock failure

```java
// ❌ WRONG — reusing stale entity
Wallet wallet = walletRepository.findById(id).orElseThrow();
// ... much later in retry ...
wallet.debit(amount);  // Still has old version!
walletRepository.save(wallet);  // Will keep failing

// ✅ CORRECT — fresh read in each retry attempt
retryTemplate.execute(ctx -> {
    Wallet wallet = walletRepository.findById(id).orElseThrow();  // Fresh read
    wallet.debit(amount);
    walletRepository.save(wallet);
    return true;
});
```

### Pitfall 2: Long transactions with optimistic locking

The longer a transaction takes, the **higher the chance** another transaction modifies the version before you commit. Keep optimistic-locked transactions as short as possible.

### Pitfall 3: Not handling version in DTOs

If you expose version to clients (e.g., for PUT updates), **always include the version in the request** so the server can detect stale updates.

---

## 6. Database-Level Comparison

### PostgreSQL MVCC (Multi-Version Concurrency Control)

PostgreSQL uses **MVCC** — every transaction sees a snapshot of the database. This is essentially optimistic at the storage level:

```
PostgreSQL Internal:
┌─────────────────────────────────────────────────┐
│  Row: id=1, balance=1000                        │
│  xmin=100 (created by tx 100)                   │
│  xmax=0   (not deleted by any tx)               │
│                                                 │
│  After UPDATE by tx 200:                        │
│  Old row: xmax=200 (marked dead by tx 200)     │
│  New row: id=1, balance=900, xmin=200          │
└─────────────────────────────────────────────────┘
```

| PostgreSQL Isolation Level | Optimistic or Pessimistic? |
|---------------------------|---------------------------|
| `READ COMMITTED` (default) | Optimistic (MVCC snapshots) |
| `REPEATABLE READ` | Optimistic (fails on write conflict) |
| `SERIALIZABLE` | Optimistic (SSI — Serializable Snapshot Isolation) |
| Explicit `FOR UPDATE` | Pessimistic |

---

## 7. Interview Quick-Fire

**Q: Can you use both optimistic and pessimistic locking together?**
A: Yes. Example: Optimistic `@Version` for normal operations + pessimistic `FOR UPDATE` for critical sections (like batch settlement).

**Q: What happens if the version column wraps around?**
A: `Long` can hold 2^63 ≈ 9.2 × 10^18 values. Even at 1 million updates/second, it would take ~292,000 years.

**Q: Is optimistic locking the same as Compare-And-Swap (CAS)?**
A: Conceptually yes. `@Version` check is a database-level CAS operation: "update only if current state matches expected state."

**Q: How does optimistic locking interact with database isolation levels?**
A: The `@Version` check works at **application level** (Hibernate adds the WHERE clause). It works correctly with all isolation levels, but `SERIALIZABLE` isolation adds its own conflict detection on top.

---

## Key Takeaway for This Project

> We chose **optimistic locking** because our wallet system has **moderate write contention** (transfers are distributed across shards, reducing per-row contention) and we handle failures via **automatic retries with exponential backoff** in the Saga orchestrator. This gives us **high throughput** without lock contention or deadlock risks.
