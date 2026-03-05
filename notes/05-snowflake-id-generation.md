# Snowflake ID Generation

## Quick Summary

**Snowflake ID** is a distributed ID generation algorithm that produces **globally unique, time-sortable, 64-bit integers** without needing coordination between nodes. Originally designed by Twitter, it's the standard for generating IDs across sharded databases.

---

## 1. Why Not Auto-Increment?

### The Problem with `BIGSERIAL` Across Shards

```
Shard 1: users table          Shard 2: users table
┌────┬─────────┐              ┌────┬─────────┐
│ id │ name    │              │ id │ name    │
├────┼─────────┤              ├────┼─────────┤
│ 1  │ Alice   │              │ 1  │ Bob     │  ← COLLISION!
│ 2  │ Charlie │              │ 2  │ Diana   │  ← COLLISION!
│ 3  │ Eve     │              │ 3  │ Frank   │  ← COLLISION!
└────┴─────────┘              └────┴─────────┘

Problem: Both shards independently auto-increment from 1.
The ID is NOT globally unique.
```

### Common Solutions

| Approach | How | Problems |
|----------|-----|----------|
| **Offset increment** | Shard 1: 1,3,5,7... Shard 2: 2,4,6,8... | Hard to add shards; non-contiguous |
| **UUID (v4)** | 128-bit random | Not sortable; poor index performance; 36 chars |
| **UUID (v7)** | 128-bit time-ordered | Better than v4, but still 128 bits |
| **Central ID service** | Dedicated service generates IDs | Single point of failure; network hop |
| **Snowflake** ✅ | 64-bit time + node + sequence | Sortable; no coordination; fits in `BIGINT` |

---

## 2. Snowflake ID Structure

A Snowflake ID is a **64-bit integer** composed of:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
├─┼───────────────────────────────────────────┼─────────┼─────────┤
│0│          41 bits: Timestamp               │10 bits  │12 bits  │
│ │          (milliseconds since epoch)       │Worker ID│Sequence │
├─┼───────────────────────────────────────────┼─────────┼─────────┤

Total: 1 + 41 + 10 + 12 = 64 bits
```

### Breakdown

| Field | Bits | Range | Purpose |
|-------|------|-------|---------|
| **Sign bit** | 1 | Always 0 | Ensures positive number |
| **Timestamp** | 41 | ~69 years from epoch | Time ordering |
| **Worker ID** | 10 | 0-1023 (1024 workers) | Node identification |
| **Sequence** | 12 | 0-4095 (4096 per ms) | Per-millisecond counter |

### Capacity

```
Timestamp: 2^41 ms = 2,199,023,255,552 ms ≈ 69.7 years
Worker ID: 2^10 = 1,024 workers
Sequence:  2^12 = 4,096 IDs per millisecond per worker

Total throughput: 1024 workers × 4096 IDs/ms = 4,194,304 IDs/ms
                = 4.19 BILLION IDs per second globally!
```

---

## 3. How It Generates an ID

```
Algorithm (pseudocode):

function generateId():
  timestamp = currentTimeMillis() - EPOCH  // Custom epoch for more years
  
  if timestamp == lastTimestamp:
    sequence = (sequence + 1) & 0xFFF     // Increment, max 4095
    if sequence == 0:
      timestamp = waitNextMillis()         // Exhausted sequence; wait
  else:
    sequence = 0                           // New millisecond; reset
  
  lastTimestamp = timestamp
  
  return (timestamp << 22)                 // Shift left 22 bits
       | (workerId << 12)                  // Shift left 12 bits
       | sequence                          // Lowest 12 bits
```

### Example

```
Timestamp: 1709568000000 ms (some time in 2024)
Worker ID: 5
Sequence:  42

Binary:
  Timestamp (41 bits): 11000110100...0000000000000
  Worker ID (10 bits): 0000000101
  Sequence  (12 bits): 000000101010

Combined: 0_11000110100...0000000000000_0000000101_000000101010

Decimal: 928586788773888001 (17-19 digit number)
```

---

## 4. Key Properties

### Time-Sortable

```
ID generated at 10:00:00 → 928586788773888001
ID generated at 10:00:01 → 928586792921088001
ID generated at 10:00:02 → 928586797068288001
                                     ↑ increases with time!

Benefit: SELECT * FROM transactions ORDER BY id
         → automatically ordered by creation time!
         → B-tree index insertions are sequential (no random I/O)
```

### Globally Unique Without Coordination

```
Worker 1 at 10:00:00.001: timestamp|worker=1|seq=0 → unique
Worker 2 at 10:00:00.001: timestamp|worker=2|seq=0 → unique (different worker bits)
Worker 1 at 10:00:00.001: timestamp|worker=1|seq=1 → unique (different sequence)
Worker 1 at 10:00:00.002: timestamp|worker=1|seq=0 → unique (different timestamp)
```

No network call needed. Each worker generates IDs independently.

### Fits in BIGINT

```
UUID v4:  550e8400-e29b-41d4-a716-446655440000  (128 bits, 36 chars as string)
Snowflake: 928586788773888001                    (64 bits, fits in BIGINT/Long)

Storage:  UUID = 16 bytes    Snowflake = 8 bytes (50% less)
Index:    UUID = random → scattered inserts → page splits → slow
          Snowflake = sequential → append-only → fast
```

---

## 5. ShardingSphere Snowflake Configuration

```yaml
# sharding.yml
rules:
  - !SHARDING
    tables:
      users:
        keyGenerateStrategy:
          column: id
          keyGeneratorName: snowflake
      wallet:
        keyGenerateStrategy:
          column: id
          keyGeneratorName: snowflake
      # ... same for all tables

    keyGenerators:
      snowflake:
        type: SNOWFLAKE
        # Optional: props:
        #   worker-id: 1
        #   max-tolerate-time-difference-milliseconds: 10
```

ShardingSphere assigns worker IDs automatically. When the app generates a Snowflake ID, it **replaces** the JPA `@GeneratedValue(strategy = GenerationType.IDENTITY)` at the ShardingSphere level.

```java
@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;  // ShardingSphere intercepts this and uses Snowflake
}
```

---

## 6. Comparison with Other ID Strategies

| Strategy | Bits | Sortable | Unique across nodes | DB compatible | Index perf |
|----------|------|----------|--------------------|----|------------|
| `BIGSERIAL` | 64 | ✅ | ❌ (per-DB) | ✅ BIGINT | ✅ |
| UUID v4 | 128 | ❌ | ✅ | ⚠️ VARCHAR/UUID | ❌ (random) |
| UUID v7 | 128 | ✅ | ✅ | ⚠️ VARCHAR/UUID | ✅ |
| ULID | 128 | ✅ | ✅ | ⚠️ VARCHAR | ✅ |
| **Snowflake** | **64** | ✅ | ✅ | ✅ BIGINT | ✅ |
| DB sequence + offset | 64 | ✅ | ✅ (if configured) | ✅ | ✅ |

---

## 7. Potential Issues

### Clock Skew

If the system clock moves **backward** (NTP adjustment, VM migration), Snowflake could generate duplicate IDs.

```
Time: 10:00:00.001 → generate ID with timestamp 1001
Clock jumps back!
Time: 10:00:00.000 → generate ID with timestamp 1000 → LOWER than previous!

ShardingSphere solution:
  props:
    max-tolerate-time-difference-milliseconds: 10
  → If clock goes back ≤ 10ms, wait. If > 10ms, throw exception.
```

### Worker ID Conflicts

If two ShardingSphere instances accidentally get the same worker ID, they could generate identical IDs.

Solution: ShardingSphere uses the machine's IP + process ID to calculate worker ID, minimizing collision risk.

### Epoch Exhaustion

41 bits of timestamp = ~69.7 years from the custom epoch. Twitter's Snowflake started at Nov 4, 2010. It will exhaust around ~2080.

---

## 8. Extracting Information from a Snowflake ID

You can **decode** a Snowflake ID to extract its timestamp:

```java
long id = 928586788773888001L;
long EPOCH = 1288834974657L;  // Twitter's epoch (Nov 4, 2010)

long timestamp = (id >> 22) + EPOCH;
long workerId = (id >> 12) & 0x3FF;   // 10 bits
long sequence = id & 0xFFF;            // 12 bits

Date createdAt = new Date(timestamp);
// → tells you WHEN the entity was created, from just the ID!
```

---

## 9. Interview Quick-Fire

**Q: Why not just use UUIDs?**
A: UUIDs are 128 bits (16 bytes vs 8), not sortable (v4), and cause random B-tree index insertions. Snowflake IDs are 64 bits, fit in BIGINT, and are time-sorted for sequential index appends.

**Q: What happens if a Snowflake generator generates more than 4096 IDs in one millisecond?**
A: It waits until the next millisecond. This is the `sequence exhaustion` scenario.

**Q: Is Snowflake truly unique?**
A: Yes, as long as: (1) worker IDs are unique across nodes, and (2) system clocks don't go backward more than the tolerance. In practice, collisions are effectively impossible.

**Q: Can you get the creation time from a Snowflake ID?**
A: Yes! Right-shift by 22 bits to get the timestamp relative to the epoch. This is useful for auditing and debugging.

---

## Key Takeaway

> We use **Snowflake IDs** (via ShardingSphere) because they are **64-bit, time-sortable, globally unique** integers that work perfectly across sharded databases. They fit in PostgreSQL BIGINT columns, produce sequential B-tree index inserts, and require **no coordination** between shards. Each shard can independently generate IDs without risk of collision.
