# ACID Properties & Distributed Transactions

## Quick Summary

**ACID** = Atomicity, Consistency, Isolation, Durability — the four guarantees a relational database provides for transactions. **Distributed transactions** try to extend these guarantees across multiple databases/services, which is fundamentally hard (see CAP theorem).

---

## 1. ACID Properties

### Atomicity — "All or Nothing"

```
BEGIN;
  UPDATE wallet SET balance = balance - 250 WHERE id = 1;
  UPDATE wallet SET balance = balance + 250 WHERE id = 2;
COMMIT;

Either BOTH updates happen, or NEITHER happens.
If the server crashes between the two updates → ROLLBACK (neither happened).
```

```
Atomicity:
  ┌────────────────────────────────┐
  │    Transaction Boundary        │
  │                                │
  │  Operation 1  ──→  ✅         │
  │  Operation 2  ──→  ✅         │
  │  Operation 3  ──→  ❌ FAIL    │  ← All 3 are rolled back
  │                                │
  │  Result: NONE of them persist  │
  └────────────────────────────────┘
```

**How PostgreSQL implements it:** Write-Ahead Log (WAL). Changes are logged before writing to data files. On crash, replay WAL to either complete or rollback the transaction.

### Consistency — "Valid State to Valid State"

The database moves from one **valid state** to another valid state. All constraints (primary keys, foreign keys, checks, triggers) must be satisfied after the transaction.

```
Before: Wallet A balance = $1000, Wallet B balance = $500
        Total = $1500

After:  Wallet A balance = $750,  Wallet B balance = $750
        Total = $1500 ✅ (conservation of money)

Constraint check: balance >= 0 → both wallets pass ✅
```

**Note:** "Consistency" in ACID is **different** from "Consistency" in CAP theorem.
- ACID Consistency: Data integrity constraints satisfied
- CAP Consistency: All nodes see the same data at the same time

### Isolation — "Transactions Don't Interfere"

Concurrent transactions behave as if they were executed **serially** (one after another).

```
Without Isolation:
  Tx1: Read balance ($1000) ──────── Debit $250 ($750) ──── Write $750
  Tx2: ────── Read balance ($1000) ──── Debit $100 ──── Write $900
  Final: $900 (WRONG! Lost Tx1's debit — "lost update")

With Isolation:
  Tx1: Read ($1000) → Write ($750) → COMMIT
  Tx2: ──── BLOCKED ──────────────── Read ($750) → Write ($650) → COMMIT
  Final: $650 (CORRECT)
```

### Isolation Levels

| Level | Dirty Read | Non-Repeatable Read | Phantom Read | Performance |
|-------|-----------|-------------------|-------------|-------------|
| `READ UNCOMMITTED` | ✅ Possible | ✅ Possible | ✅ Possible | Fastest |
| `READ COMMITTED` | ❌ No | ✅ Possible | ✅ Possible | Fast (PostgreSQL default) |
| `REPEATABLE READ` | ❌ No | ❌ No | ✅ Possible | Moderate |
| `SERIALIZABLE` | ❌ No | ❌ No | ❌ No | Slowest |

**Read Phenomena:**

```
Dirty Read: Reading uncommitted data from another transaction
  Tx1: UPDATE balance = 750 (not committed yet)
  Tx2: SELECT balance → sees 750 (dirty!)
  Tx1: ROLLBACK → balance is actually still 1000
  Tx2: used wrong value!

Non-Repeatable Read: Same query returns different results
  Tx1: SELECT balance WHERE id=1 → $1000
  Tx2: UPDATE balance = 750 WHERE id=1; COMMIT;
  Tx1: SELECT balance WHERE id=1 → $750 (different!)

Phantom Read: New rows appear in repeated query
  Tx1: SELECT * FROM wallet WHERE user_id=1 → 2 rows
  Tx2: INSERT INTO wallet (user_id=1, ...); COMMIT;
  Tx1: SELECT * FROM wallet WHERE user_id=1 → 3 rows (phantom!)
```

### Durability — "Committed = Permanent"

Once a transaction is committed, it survives power failures, crashes, and disk failures.

```
COMMIT; → WAL flushed to disk → data eventually written to data files
                ↑
                Even if server crashes here, WAL can replay the commit
```

**How PostgreSQL implements it:**
- `fsync` ensures WAL is on physical disk before reporting commit
- Checkpoints periodically flush dirty pages to data files
- Base backups + WAL archiving for disaster recovery

---

## 2. Why ACID Breaks in Distributed Systems

### Single Database ACID

```
┌─────────────────────────────────────┐
│         PostgreSQL Instance          │
│                                     │
│  BEGIN;                             │
│    Debit Wallet A                   │   ← Same database engine
│    Credit Wallet B                  │   ← Same WAL, same lock manager
│  COMMIT;                            │
│                                     │
│  ACID: ✅ Guaranteed                │
└─────────────────────────────────────┘
```

### Distributed Database ACID (The Problem)

```
┌──────────────────┐              ┌──────────────────┐
│  shardwallet1    │              │  shardwallet2    │
│  (PostgreSQL)    │              │  (PostgreSQL)    │
│                  │              │                  │
│  BEGIN;          │              │  BEGIN;          │
│  Debit Wallet A  │              │  Credit Wallet B │
│  COMMIT;         │              │  COMMIT;         │
│                  │              │                  │
│  Different WAL   │              │  Different WAL   │
│  Different locks │              │  Different locks │
└──────────────────┘              └──────────────────┘

These are TWO INDEPENDENT transactions!
- No shared lock manager → no isolation between shards
- No shared WAL → no atomicity across shards
- No shared commit protocol → one can commit while other fails
```

---

## 3. CAP Theorem

In a **distributed system**, you can only guarantee **two** of three properties:

```
          Consistency (C)
               /\
              /  \
             / CA \
            /      \
           /────────\
          /    CP    \
         /            \
        / ─ ─ ─ ─ ─ ─ \
       /       AP       \
      ──────────────────── 
 Availability (A)    Partition Tolerance (P)
```

| Property | Meaning |
|----------|---------|
| **Consistency** | All nodes see the same data at the same time |
| **Availability** | Every request gets a response (not error/timeout) |
| **Partition Tolerance** | System works despite network partitions |

### In Practice (Network Partitions Are Inevitable)

Since network partitions **will** happen in any distributed system, the real choice is:

| Choice | What You Get | What You Lose | Example |
|--------|-------------|---------------|---------|
| **CP** | Consistency + Partition Tolerance | Availability during partition | Traditional banking, 2PC |
| **AP** | Availability + Partition Tolerance | Consistency during partition | Our Saga approach, eventual consistency |

Our wallet system chooses **AP**: the system stays available (accepts transfers) even if one shard is temporarily unreachable, at the cost of brief inconsistency (PENDING status visible between saga steps).

---

## 4. Distributed Transaction Solutions

### Solution 1: Two-Phase Commit (2PC) — Strong Consistency

```
Coordinator → PREPARE → all participants
             ← VOTE YES/NO ← all participants
Coordinator → COMMIT/ABORT → all participants

✅ ACID across nodes
❌ Blocking (coordinator failure blocks all)
❌ High latency (multiple round-trips)
❌ Limited scalability
```

### Solution 2: Saga — Eventual Consistency ← **Our Approach**

```
Step 1: Local ACID transaction on Shard 1
Step 2: Local ACID transaction on Shard 2
Step 3: Local ACID transaction on Shard 1

If failure: compensate completed steps in reverse order

✅ Non-blocking
✅ High availability
✅ Scales well
❌ Intermediate states visible
❌ Must design compensations
```

### Solution 3: Outbox Pattern — Reliable Event Publishing

```
Same transaction:
  1. Update business data
  2. Write event to outbox table
Separate process:
  3. Poll outbox → publish to message broker
  4. Consumer processes event

✅ At-least-once delivery
✅ No distributed transaction needed
❌ Requires message broker (Kafka, RabbitMQ)
❌ Eventual consistency
```

### Solution 4: Change Data Capture (CDC)

```
Database → WAL → Debezium → Kafka → Consumer

✅ No application code changes
✅ Captures all changes (including direct DB updates)
❌ Complex infrastructure
❌ Eventual consistency
```

### Comparison

| Solution | Consistency | Availability | Complexity | Latency |
|----------|------------|-------------|------------|---------|
| 2PC | Strong | Low | Medium | High |
| Saga | Eventual | High | Medium | Low |
| Outbox | Eventual | High | Medium-High | Medium |
| CDC | Eventual | High | High | Medium |
| Event Sourcing | Eventual | High | Very High | Low |

---

## 5. BASE vs ACID

| ACID | BASE |
|------|------|
| **A**tomicity | **B**asically **A**vailable |
| **C**onsistency | **S**oft state |
| **I**solation | **E**ventual consistency |
| **D**urability | |

```
ACID: "Transactions are all-or-nothing with full isolation"
BASE: "System is available, state may be temporarily inconsistent, 
       but will converge to consistency eventually"

Our system: ACID within each shard + BASE across shards
  - Within a shard: full ACID (PostgreSQL transaction)
  - Across shards: BASE (saga provides eventual consistency)
```

---

## 6. Our Approach: Local ACID + Global Eventual Consistency

```
┌──────────────────────────────────────────────────────────────┐
│                   Saga Orchestrator                            │
│              (Global Eventual Consistency)                     │
│                                                              │
│  ┌───────────────────┐     ┌───────────────────┐            │
│  │  Shard 1 (ACID)   │     │  Shard 2 (ACID)   │            │
│  │                   │     │                   │            │
│  │  BEGIN;           │     │  BEGIN;           │            │
│  │  Debit Wallet A   │     │  Credit Wallet B  │            │
│  │  COMMIT;          │     │  COMMIT;          │            │
│  │                   │     │                   │            │
│  │  Full ACID here   │     │  Full ACID here   │            │
│  └───────────────────┘     └───────────────────┘            │
│                                                              │
│  Between steps: Eventual consistency (PENDING state visible) │
│  After saga: Consistent (SUCCESS or COMPENSATED)             │
└──────────────────────────────────────────────────────────────┘
```

Each step runs a **local ACID transaction** with:
- `@Transactional` on each step method
- `@Version` for optimistic locking (isolation)
- PostgreSQL WAL for durability
- Single-shard atomic operations

The saga provides **eventual consistency** across shards:
- All steps complete → globally consistent (COMPLETED)
- Any step fails → compensation restores consistency (COMPENSATED)
- Brief window of inconsistency between steps is acceptable

---

## 7. Interview Quick-Fire

**Q: What is ACID?**
A: Four guarantees of database transactions: Atomicity (all-or-nothing), Consistency (valid state transitions), Isolation (concurrent transactions don't interfere), Durability (committed data survives crashes).

**Q: What's the difference between ACID consistency and CAP consistency?**
A: ACID consistency = data integrity constraints (FKs, checks). CAP consistency = all distributed nodes see the same data at the same time. Different concepts, confusingly named.

**Q: Can you have ACID across a distributed system?**
A: Yes, using 2PC, but at the cost of availability and performance. That's why most distributed systems choose eventual consistency (BASE/Saga).

**Q: What isolation level do you use?**
A: PostgreSQL's default `READ COMMITTED`. Combined with `@Version` (optimistic locking), this prevents lost updates without the overhead of `SERIALIZABLE`.

**Q: What does BASE stand for?**
A: Basically Available, Soft State, Eventually Consistent. It's the opposite philosophy of ACID — prioritize availability and accept temporary inconsistency.

---

## Key Takeaway

> Our wallet system uses **local ACID transactions** within each PostgreSQL shard (via `@Transactional` + `@Version`) and **global eventual consistency** across shards (via the Saga pattern). This gives us the best of both worlds: strong guarantees within a shard, and high availability across shards. Brief intermediate states are acceptable because the saga will always converge to a consistent final state (COMPLETED or COMPENSATED).
