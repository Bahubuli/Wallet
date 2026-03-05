# Two-Phase Commit (2PC) vs Saga Pattern

## Quick Summary

| Aspect | Two-Phase Commit (2PC) | Saga Pattern |
|--------|----------------------|--------------|
| **Consistency** | Strong (ACID) | Eventual |
| **Availability** | Lower (blocking) | Higher (non-blocking) |
| **Latency** | Higher (lock held across phases) | Lower (no global lock) |
| **Failure mode** | Blocking (coordinator failure) | Non-blocking (compensation) |
| **CAP theorem** | CP (Consistency + Partition tolerance) | AP (Availability + Partition tolerance) |

---

## 1. Two-Phase Commit (2PC) Explained

2PC is a **distributed consensus protocol** that ensures all participants in a distributed transaction either **all commit** or **all abort**.

### The Two Phases

```
Phase 1: PREPARE (Voting)
══════════════════════════

  Coordinator                   Participant A              Participant B
      │                              │                          │
      │───── PREPARE ───────────────►│                          │
      │───── PREPARE ──────────────────────────────────────────►│
      │                              │                          │
      │◄──── VOTE YES ──────────────│                          │
      │◄──── VOTE YES ───────────────────────────────────────── │
      │                              │                          │
      │  (All voted YES)             │                          │
      │                              │                          │

Phase 2: COMMIT (Decision)
══════════════════════════

      │───── COMMIT ────────────────►│                          │
      │───── COMMIT ───────────────────────────────────────────►│
      │                              │                          │
      │◄──── ACK ───────────────────│                          │
      │◄──── ACK ────────────────────────────────────────────── │
      │                              │                          │
      ✅ Transaction Complete
```

### If Any Participant Votes NO

```
Phase 1:
      │───── PREPARE ───────────────►│  Participant A
      │───── PREPARE ───────────────►│  Participant B
      │◄──── VOTE YES ──────────────│  A says YES
      │◄──── VOTE NO ───────────────│  B says NO (e.g., constraint violation)

Phase 2:
      │───── ROLLBACK ──────────────►│  Tell A to rollback
      │───── ROLLBACK ──────────────►│  Tell B to rollback
      ❌ Transaction Aborted
```

### 2PC Problems

#### Problem 1: Blocking

```
Timeline:
──────────────────────────────────────────────────────────────►

Participant A: PREPARED ──────── WAITING ──────── WAITING ─────
Participant B: PREPARED ──────── WAITING ──────── WAITING ─────
Coordinator:   PREPARE sent ──── ████ CRASHED ████

Both participants hold LOCKS and WAIT indefinitely!
No participant can unilaterally decide to commit or abort.
This is the "blocking problem" of 2PC.
```

#### Problem 2: Coordinator Single Point of Failure

If the coordinator crashes after sending PREPARE but before sending COMMIT/ABORT, all participants are stuck in an **uncertain state** holding locks.

#### Problem 3: Latency

```
2PC Latency = Network RTT × 4 + Lock Hold Time
              (PREPARE + VOTE + COMMIT + ACK → 4 network hops)

Saga Latency = Network RTT × (N steps) — but NO global lock holding
```

In a 2-shard wallet system:
- **2PC**: ~40ms (4 hops × 10ms network) + lock hold time
- **Saga**: ~30ms (3 steps × 10ms) — no lock contention

---

## 2. Saga Pattern Recap

Instead of coordinating a global commit, break the transaction into **independent local transactions** with **compensating actions**:

```
Step 1: Debit Source Wallet   (compensate: Credit Source Wallet)
Step 2: Credit Dest Wallet    (compensate: Debit Dest Wallet)
Step 3: Update Txn Status     (compensate: Revert Status)

If Step 2 fails → compensate Step 1 (credit back the debit)
```

No global lock. No voting phase. Each step is an independent ACID transaction.

---

## 3. Head-to-Head Comparison

### Consistency Model

| | 2PC | Saga |
|---|-----|------|
| **Guarantee** | Strong consistency (all-or-nothing) | Eventual consistency |
| **Intermediate states** | Not visible (locked) | Visible (temporarily inconsistent) |
| **Isolation** | Full (via locks) | None between steps |
| **Read anomalies** | None during transaction | Possible dirty reads |

**Example — Intermediate State in Saga:**

```
Time T1: Debit Wallet A ($1000 → $750)     ← $250 has "disappeared"
Time T2: Credit Wallet B ($500 → $750)     ← $250 has "appeared"

Between T1 and T2, the system is inconsistent:
Total money = $750 + $500 = $1250 (should be $1500)
This is visible to other queries!

2PC would hold locks on both rows, preventing this intermediate state.
```

### Availability & Partition Tolerance

```
CAP Theorem positioning:

            Consistency
               /\
              /  \
             /    \
            / 2PC  \
           /────────\
          /          \
         /   Saga     \
        /──────────────\
       /                \
      ──────────────────── 
   Availability    Partition Tolerance
```

| | 2PC | Saga |
|---|-----|------|
| **Network partition** | Blocks (can't reach coordinator) | Continues (compensates later) |
| **Node failure** | Blocks until recovery | Retry + compensate |
| **Available during failure** | ❌ No | ✅ Yes (with caveats) |

### Performance

```
Throughput vs. # of Participants:

Throughput
    ▲
    │  Saga    ────────────────────────
    │
    │  2PC     ────────╲
    │                   ╲
    │                    ╲───────────
    │
    └──────────────────────────────────► # Participants
       2      5      10      20
```

| Metric | 2PC | Saga |
|--------|-----|------|
| **Lock duration** | Entire transaction (all participants) | Per-step only |
| **Throughput** | Degrades with more participants | Scales linearly |
| **Latency** | 4 network round-trips minimum | N steps (no global coordination) |
| **Connection holding** | All participants hold connections | Only active step holds connection |

### Failure Recovery

| Failure Scenario | 2PC | Saga |
|------------------|-----|------|
| **Participant fails after PREPARE** | Block until recovery | Compensate completed steps |
| **Coordinator fails** | All participants block | Saga state in DB; resume on restart |
| **Network partition** | Uncertain state (block) | Local step succeeds/fails; compensate |
| **Partial failure** | Global rollback | Per-step compensation |

---

## 4. Why We Chose Saga Over 2PC

### Reason 1: Sharded PostgreSQL Databases

```
┌──────────────────┐              ┌──────────────────┐
│  shardwallet1    │              │  shardwallet2    │
│  (PostgreSQL)    │              │  (PostgreSQL)    │
│                  │              │                  │
│  No built-in     │              │  No shared       │
│  distributed     │◄────────────►│  transaction     │
│  transaction     │  Independent │  manager         │
│  protocol        │  servers     │                  │
└──────────────────┘              └──────────────────┘
```

- PostgreSQL doesn't natively support 2PC across independent servers
- We'd need an **external transaction manager** (like Atomikos, Narayana)
- ShardingSphere JDBC doesn't include a 2PC implementation for cross-shard writes

### Reason 2: Availability Over Consistency

In a **wallet system**, users expect:
- Transfers to **not block** even when one shard is slow
- The system to be **available** even during partial failures
- **Eventual consistency** is acceptable (users can see "PENDING" status)

### Reason 3: Performance at Scale

```
2PC with 2 shards:
  PREPARE → Shard 1    (10ms)
  PREPARE → Shard 2    (10ms)
  VOTE ← Shard 1       (10ms)
  VOTE ← Shard 2       (10ms)
  COMMIT → Shard 1     (10ms)
  COMMIT → Shard 2     (10ms)
  Total: ~60ms + lock holding time
  Both shards are LOCKED during this entire time

Saga with 2 shards:
  Debit Shard 1         (15ms, lock released immediately)
  Credit Shard 2        (15ms, lock released immediately)
  Update Txn Status     (10ms)
  Total: ~40ms, NO global lock
```

### Reason 4: Simpler Failure Handling

```
2PC Failure:
  Coordinator crashes → participants BLOCKED → manual intervention needed
  Network partition → UNCERTAIN STATE → potential data inconsistency on recovery
  Solution: timeouts + heuristic decisions (but can cause inconsistency!)

Saga Failure:
  Any step fails → orchestrator runs compensation in reverse order
  Server crashes → on restart, find sagas in RUNNING/COMPENSATING state → resume
  Network partition → step fails → compensate → saga is COMPENSATED
  Solution: automatic, deterministic, traceable
```

### Reason 5: Observability

Our saga implementation provides:
- Every step tracked in `saga_step` table with status, timestamps, retry counts
- Full execution context stored as JSONB
- Error messages captured at step level
- Saga-level status visible via API

2PC gives you: success or timeout. No intermediate visibility.

---

## 5. When 2PC IS the Better Choice

| Scenario | Why 2PC |
|----------|---------|
| **Banking core ledger** | Zero tolerance for intermediate inconsistency |
| **Same database cluster** | No network partition; 2PC is nearly free |
| **Few participants (2-3)** | Lock overhead is minimal |
| **XA-compatible resources** | JDBC XA drivers available |
| **Regulatory requirement** | Must prove ACID compliance |
| **Homogeneous systems** | All participants speak the same protocol |

---

## 6. Alternatives We Considered

### Alternative 1: Transactional Outbox Pattern

Write events to an outbox table in the same transaction, then poll/CDC them to a message broker.

```
BEGIN;
  UPDATE wallet SET balance = balance - 250 WHERE id = 1;
  INSERT INTO outbox (event_type, payload) VALUES ('WALLET_DEBITED', '...');
COMMIT;
-- Separate process reads outbox and publishes to Kafka
```

**Verdict:** Good for event-driven architectures, but we don't have Kafka yet.

### Alternative 2: TCC (Try-Confirm-Cancel)

Three-phase approach where each participant **reserves** resources first:

```
Try:     Reserve $250 from Wallet A (mark as "held")
Try:     Reserve $250 credit for Wallet B
Confirm: Actually debit Wallet A
Confirm: Actually credit Wallet B
Cancel:  Release holds if any Try fails
```

**Verdict:** More complex than saga; requires "reservation" model on wallets.

### Alternative 3: Event Sourcing + CQRS

Store all changes as immutable events. Derive current state by replaying events.

**Verdict:** Complete architecture overhaul; overkill for our current scale.

---

## 7. Decision Matrix

| Criteria (Weight) | 2PC | Saga | TCC | Outbox |
|-------------------|-----|------|-----|--------|
| Availability (High) | ⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| Consistency (Med) | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐⭐ |
| Simplicity (High) | ⭐⭐⭐ | ⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐ |
| Performance (High) | ⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐⭐ |
| Observability (Med) | ⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐⭐ |
| Infra needed (Low) | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | ⭐⭐ (needs Kafka) |
| **Total** | **17** | **27** ✅ | **19** | **19** |

---

## 8. Interview Quick-Fire

**Q: Why not use 2PC?**
A: 2PC is blocking — if the coordinator fails, all participants hold locks indefinitely. With sharded PostgreSQL, we don't have a built-in 2PC coordinator, and we prioritize availability over strong consistency.

**Q: What do you lose with Saga vs 2PC?**
A: Isolation. Between saga steps, intermediate states (e.g., debited but not yet credited) are visible. We mitigate this with status tracking (PENDING transactions) and the saga state machine.

**Q: Can Saga guarantee "exactly-once" execution?**
A: No. Saga provides "at-least-once" semantics. To achieve effectively-once, you need idempotency keys (on our roadmap).

**Q: What is the "saga log"?**
A: Our `saga_instance` + `saga_step` tables. They persist the complete execution history, enabling recovery after crashes and providing audit trail.

---

## Key Takeaway

> **2PC = Strong consistency at the cost of availability and performance.**
> **Saga = High availability and performance at the cost of temporary inconsistency.**
>
> For a sharded wallet system where PostgreSQL shards are independent databases, Saga is the natural choice. We accept brief intermediate states (PENDING status) in exchange for non-blocking transfers that survive partial failures.
