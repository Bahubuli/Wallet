# Database Sharding — Deep Dive

## Quick Summary

**Sharding** = Splitting data across multiple independent database instances based on a **shard key**, so each instance holds a subset of the total data.

---

## 1. Why Shard?

### The Single Database Problem

```
All data on ONE database:
┌─────────────────────────────────────┐
│          PostgreSQL                  │
│  Users: 50 million rows            │
│  Wallets: 100 million rows         │
│  Transactions: 1 billion rows      │
│                                     │
│  CPU: 100% │ RAM: 128GB full       │
│  Disk I/O: Saturated               │
│  Connections: 500 (maxed out)      │
└─────────────────────────────────────┘

Problems:
❌ Vertical scaling has limits (biggest server = still one server)
❌ Single point of failure
❌ Backup takes hours
❌ Schema migrations lock entire dataset
```

### After Sharding

```
┌──────────────────┐    ┌──────────────────┐
│  shardwallet1    │    │  shardwallet2    │
│  Users: 25M      │    │  Users: 25M      │
│  Wallets: 50M    │    │  Wallets: 50M    │
│  Txns: 500M      │    │  Txns: 500M      │
│                  │    │                  │
│  CPU: 50%        │    │  CPU: 50%        │
│  RAM: 64GB       │    │  RAM: 64GB       │
│  Conn: 250       │    │  Conn: 250       │
└──────────────────┘    └──────────────────┘

Benefits:
✅ Horizontal scaling (add more shards)
✅ Each shard is independently sized
✅ Parallel query execution
✅ Fault isolation (shard 1 down ≠ shard 2 down)
```

---

## 2. Sharding Strategies

### Strategy 1: Hash-Based (Modulo) Sharding ← **Our Approach**

```
shard_number = shard_key % number_of_shards + 1

Example:
  user_id = 101 → 101 % 2 + 1 = shard 2
  user_id = 102 → 102 % 2 + 1 = shard 1
  user_id = 103 → 103 % 2 + 1 = shard 2
  user_id = 104 → 104 % 2 + 1 = shard 1
```

**Pros:**
- Even data distribution (assuming uniform IDs)
- Simple, deterministic routing
- No lookup table needed

**Cons:**
- **Resharding is expensive** — adding a 3rd shard changes all `% 2` to `% 3`, requiring data migration
- Range queries require scatter-gather across all shards
- Hotspots if certain IDs are more active

### Strategy 2: Range-Based Sharding

```
user_id 1 - 1,000,000     → Shard 1
user_id 1,000,001 - 2,000,000 → Shard 2
user_id > 2,000,000        → Shard 3
```

**Pros:**
- Range queries efficient (e.g., "all users created this month")
- Easy to add new shards (just extend ranges)

**Cons:**
- **Uneven distribution** — shard 1 may have most active users
- Hotspot on latest shard (all new users go there)

### Strategy 3: Directory-Based Sharding

```
Lookup table:
┌─────────┬───────┐
│ user_id │ shard │
├─────────┼───────┤
│ 101     │ 1     │
│ 102     │ 2     │
│ 103     │ 1     │
└─────────┴───────┘
```

**Pros:**
- Maximum flexibility
- Can rebalance without changing algorithm

**Cons:**
- Lookup table = single point of failure
- Extra hop for every query
- Lookup table itself needs sharding at scale

### Strategy 4: Consistent Hashing

```
Hash Ring:
        0°
        │
   ┌────┼────┐
  330°  │   30°
   │    │    │
  S3    │   S1     ← Shard positions on ring
   │    │    │
  270°──┼──90°
   │    │    │
  S2    │   
   │    │    │
  210°──┼──150°
        │
       180°

user_id → hash → position on ring → nearest shard clockwise
```

**Pros:**
- Adding/removing shard only affects adjacent nodes
- Minimal data movement on resharding (only ~1/N of data moves)

**Cons:**
- More complex implementation
- Virtual nodes needed for even distribution
- Not directly supported by ShardingSphere INLINE

---

## 3. Our Sharding Configuration

### ShardingSphere JDBC Setup

```yaml
# sharding.yml
dataSources:
  shardwallet1:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    jdbcUrl: jdbc:postgresql://localhost:5432/shardwallet1
    username: ${POSTGRES_USER}
    password: ${POSTGRES_PASS}
  shardwallet2:
    dataSourceClassName: com.zaxxer.hikari.HikariDataSource
    jdbcUrl: jdbc:postgresql://localhost:5432/shardwallet2
    username: ${POSTGRES_USER}
    password: ${POSTGRES_PASS}
```

### Shard Key Choices

```
┌───────────────────┬─────────────────┬───────────────────────────────┐
│ Table             │ Shard Key       │ Algorithm                      │
├───────────────────┼─────────────────┼───────────────────────────────┤
│ users             │ id              │ shardwallet${id % 2 + 1}     │
│ wallet            │ user_id         │ shardwallet${user_id % 2 + 1}│ ← Co-location!
│ transactions      │ id              │ shardwallet${id % 2 + 1}     │
│ saga_instance     │ id              │ shardwallet${id % 2 + 1}     │
│ saga_step         │ saga_instance_id│ shardwallet${saga_inst_id%2+1}│ ← Co-location!
└───────────────────┴─────────────────┴───────────────────────────────┘
```

### Data Flow Example

```
User Alice (id=101):
  101 % 2 + 1 = 2 → shardwallet2

Alice's Wallet (user_id=101):
  101 % 2 + 1 = 2 → shardwallet2  ← Same shard as Alice!

User Bob (id=102):
  102 % 2 + 1 = 1 → shardwallet1

Bob's Wallet (user_id=102):
  102 % 2 + 1 = 1 → shardwallet1  ← Same shard as Bob!
```

### How ShardingSphere Routes Queries

```
Application:  SELECT * FROM wallet WHERE user_id = 101

ShardingSphere:
  1. Parse SQL → extract shard key (user_id = 101)
  2. Apply algorithm: 101 % 2 + 1 = 2
  3. Route to shardwallet2
  4. Execute: SELECT * FROM wallet WHERE user_id = 101
  5. Return results

Application:  SELECT * FROM wallet (no WHERE clause on shard key)

ShardingSphere:
  1. Parse SQL → no shard key in WHERE
  2. SCATTER-GATHER: execute on ALL shards
  3. Merge results
  4. Return combined results
```

---

## 4. Shard Key Selection — Critical Design Decision

### Why `wallet.user_id` Instead of `wallet.id`?

```
Option A: Shard wallet by wallet.id
  User 101 → shardwallet2
  Wallet 501 (user 101) → 501 % 2 + 1 = shardwallet2  ← Might match
  Wallet 502 (user 101) → 502 % 2 + 1 = shardwallet1  ← DIFFERENT SHARD!

  Result: User and wallet on DIFFERENT shards
  Problem: JOIN requires cross-shard query → slow!

Option B: Shard wallet by wallet.user_id ← OUR CHOICE
  User 101 → 101 % 2 + 1 = shardwallet2
  Wallet (user_id=101) → 101 % 2 + 1 = shardwallet2  ← ALWAYS same shard!

  Result: User and all their wallets on SAME shard
  Benefit: Local JOINs, no cross-shard queries for "get user's wallets"
```

### Same Logic for saga_step

```
saga_step sharded by saga_instance_id (not step id):
  saga_instance 301 → shardwallet2
  saga_step (saga_instance_id=301) → shardwallet2  ← Same shard!

Benefit: "Get all steps for saga X" is a single-shard query.
```

---

## 5. Cross-Shard Operations

### The Challenge: Transferring Between Shards

```
Alice (shardwallet2) → Bob (shardwallet1):

Step 1: Debit Alice's wallet
  → Routes to shardwallet2 (user_id=101 → shard 2)
  → Local ACID transaction on shard 2

Step 2: Credit Bob's wallet
  → Routes to shardwallet1 (user_id=102 → shard 1)
  → Local ACID transaction on shard 1

❌ These two operations CANNOT be in a single database transaction!
✅ Solution: Saga Pattern coordinates them with compensation
```

### Why Not `JOIN` or `FOREIGN KEY` Across Shards?

```
-- This will NOT work in a sharded setup:
SELECT u.name, w.balance
FROM users u JOIN wallet w ON u.id = w.user_id
WHERE u.id = 101;

-- ShardingSphere can handle it IF both tables shard to same key
-- (which they do: users.id and wallet.user_id)
-- But cross-shard JOINs (user on shard 1, wallet on shard 2) are slow

-- Foreign keys CANNOT span shards:
ALTER TABLE wallet ADD FOREIGN KEY (user_id) REFERENCES users(id);
-- ❌ This only works WITHIN a single database
```

---

## 6. ShardingSphere JDBC Architecture

```
┌─────────────────────────────────────────────┐
│              Your Application               │
│  (Spring Boot + JPA/Hibernate)              │
│                                             │
│  EntityManager → generates SQL              │
└───────────────────────┬─────────────────────┘
                        │ JDBC calls
┌───────────────────────▼─────────────────────┐
│          ShardingSphere JDBC Driver          │
│                                             │
│  1. SQL Parser (understands your SQL)       │
│  2. Shard Router (determines target shard)  │
│  3. SQL Rewriter (adjusts SQL if needed)    │
│  4. Executor (sends to actual shards)       │
│  5. Result Merger (combines results)        │
└────────┬──────────────────────┬─────────────┘
         │                      │
         ▼                      ▼
┌─────────────────┐    ┌─────────────────┐
│  shardwallet1   │    │  shardwallet2   │
│  (HikariCP)     │    │  (HikariCP)     │
│  PostgreSQL     │    │  PostgreSQL     │
└─────────────────┘    └─────────────────┘
```

Key: **ShardingSphere sits between your app and the databases**, acting as a smart JDBC driver. Your application code doesn't know about sharding.

---

## 7. Pros and Cons of Sharding

### Pros
| Benefit | Details |
|---------|---------|
| **Horizontal scalability** | Add shards as data grows |
| **Fault isolation** | One shard down doesn't affect others |
| **Parallel execution** | Queries can run on multiple shards simultaneously |
| **Smaller indexes** | Each shard has smaller B-trees → faster queries |
| **Independent maintenance** | Backup, vacuum, reindex per shard |
| **Geographic distribution** | Place shards near users |

### Cons
| Drawback | Details |
|----------|---------|
| **Cross-shard queries** | Scatter-gather is slow; avoid if possible |
| **Cross-shard transactions** | Need Saga/2PC pattern |
| **Schema changes** | Must apply to ALL shards |
| **Resharding** | Adding shards requires data migration |
| **Complexity** | More moving parts, harder to debug |
| **No cross-shard FKs** | Referential integrity is application-level |
| **Reporting/analytics** | Aggregations across shards are expensive |
| **Operational overhead** | N databases to monitor, backup, patch |

---

## 8. Sharding Anti-Patterns

### Anti-Pattern 1: Shard Too Early

```
❌ "We might get 100M users someday, let's shard now"
✅ Start with one database, shard when you hit real limits

Rule of thumb: Shard when:
  - Single DB exceeds 1TB or 100M rows in hot tables
  - Read replicas can't handle read load
  - Write throughput exceeds single node capacity
```

### Anti-Pattern 2: Wrong Shard Key

```
❌ Sharding wallet by wallet.id when most queries are by user_id
   → Every "get user's wallets" query hits ALL shards

✅ Shard by the most common query predicate (user_id)
```

### Anti-Pattern 3: Too Many Shards

```
❌ 100 shards for 1 million rows (10K rows per shard)
   → Overhead of managing 100 databases for tiny data

✅ 2-4 shards is enough until you reach hundreds of millions of rows
```

### Anti-Pattern 4: Scatter-Gather Everything

```
❌ SELECT * FROM transactions WHERE status = 'PENDING'
   → ShardingSphere sends to ALL shards, merges results
   → N times slower than single-shard query

✅ Always include shard key in WHERE clause when possible
```

---

## 9. Resharding / Adding Shards

### The Problem

```
Current: 2 shards (id % 2 + 1)
  id=1 → shard 2    id=2 → shard 1    id=3 → shard 2    id=4 → shard 1

After adding shard 3: (id % 3 + 1)
  id=1 → shard 2    id=2 → shard 3 ❗  id=3 → shard 1 ❗  id=4 → shard 2 ❗

~67% of data needs to move! This is the biggest pain of hash sharding.
```

### Solutions

| Approach | How | Migration Cost |
|----------|-----|----------------|
| **Consistent Hashing** | Hash ring with virtual nodes | ~1/N of data moves |
| **Fixed shard count** | Over-provision shards (e.g., 256) and map multiple to one DB | Remap, don't move |
| **Online resharding** | Dual-write during migration; backfill; switch | Zero downtime but complex |
| **ShardingSphere scaling** | Supports online scaling with pipeline mode | Built-in tooling |

---

## 10. Our FlywayMigration Strategy for Shards

Flyway runs **independently on each physical shard** BEFORE ShardingSphere initializes:

```java
// DataSourceConfig.java
@Bean
public DataSource dataSource() throws Exception {
    // Run Flyway on EACH shard directly
    for (String url : new String[]{url1, url2}) {
        Flyway.configure()
            .dataSource(url, postgresUser, postgresPass)
            .baselineOnMigrate(true)
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }
    // THEN create ShardingSphere DataSource
    return YamlShardingSphereDataSourceFactory.createDataSource(tempYaml.toFile());
}
```

Why? Because ShardingSphere JDBC intercepts SQL and routes it — Flyway's `CREATE TABLE` would only run on one shard otherwise.

---

## 11. Interview Quick-Fire

**Q: What's the difference between sharding and partitioning?**
A: **Partitioning** splits data within a **single database** (e.g., PostgreSQL table partitioning). **Sharding** splits data across **multiple independent databases/servers**.

**Q: How do you handle auto-increment IDs across shards?**
A: We use **Snowflake IDs** — globally unique, time-sortable IDs generated by ShardingSphere. Never use auto-increment across shards (ID collisions).

**Q: What happens when you query without the shard key?**
A: ShardingSphere does a **scatter-gather** — sends the query to ALL shards, collects results, and merges them. This is slower than single-shard queries.

**Q: How do you handle aggregations across shards?**
A: ShardingSphere supports basic aggregations (COUNT, SUM, AVG). For complex analytics, use a separate OLAP system (e.g., ClickHouse, BigQuery) with data replicated from shards.

**Q: Can you shard by multiple keys?**
A: Not easily. You pick ONE shard key per table. If you need to query by different keys efficiently, maintain a secondary index table or use a search engine.

---

## Key Takeaway

> We use **hash-based sharding** (`key % 2 + 1`) via **ShardingSphere JDBC** with **data co-location** (wallet sharded by user_id to stay on the same shard as the user). Cross-shard transfers are coordinated by the **Saga pattern**. Snowflake IDs prevent ID collisions, and Flyway runs independently on each physical shard.
