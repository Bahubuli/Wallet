# Data Co-location Strategy

## Quick Summary

**Data co-location** = Designing shard keys so that **related data lands on the same shard**, eliminating cross-shard joins and enabling local ACID transactions on related entities.

---

## 1. The Problem Without Co-location

```
If wallet sharded by wallet.id (not user_id):

User 101 → shard 2 (101 % 2 + 1 = 2)
Wallet 501 (user 101) → shard 2 (501 % 2 + 1 = 2)  ← Lucky match
Wallet 502 (user 101) → shard 1 (502 % 2 + 1 = 1)  ← DIFFERENT SHARD!

Query: "Get all wallets for user 101"
  → Must query BOTH shards (scatter-gather)
  → 2x network round trips
  → Results merged in application

Query: "Join user with their wallets"
  → Cross-shard join — very expensive or impossible
```

---

## 2. Our Co-location Design

```
User and Wallet co-located by user_id:

User 101 → 101 % 2 + 1 = shard 2
All wallets with user_id=101 → 101 % 2 + 1 = shard 2  ← ALWAYS same shard!

User 102 → 102 % 2 + 1 = shard 1
All wallets with user_id=102 → 102 % 2 + 1 = shard 1  ← ALWAYS same shard!
```

```
shardwallet1                    shardwallet2
┌──────────────────────┐       ┌──────────────────────┐
│ User 102 (Bob)       │       │ User 101 (Alice)     │
│ Wallet A (user=102)  │       │ Wallet X (user=101)  │
│ Wallet B (user=102)  │       │ Wallet Y (user=101)  │
│                      │       │                      │
│ User 104 (Diana)     │       │ User 103 (Charlie)   │
│ Wallet C (user=104)  │       │ Wallet Z (user=103)  │
└──────────────────────┘       └──────────────────────┘

Key: user and their wallets ALWAYS on the same shard
```

### ShardingSphere Configuration

```yaml
# Users sharded by their own id
users:
  databaseStrategy:
    standard:
      shardingColumn: id
      shardingAlgorithmName: db-inline    # id % 2 + 1

# Wallets sharded by user_id (NOT wallet.id!)
wallet:
  databaseStrategy:
    standard:
      shardingColumn: user_id
      shardingAlgorithmName: user-inline  # user_id % 2 + 1

# Same algorithm, same formula → guaranteed co-location
shardingAlgorithms:
  db-inline:
    type: INLINE
    props:
      algorithm-expression: shardwallet$->{id % 2 + 1}
  user-inline:
    type: INLINE
    props:
      algorithm-expression: shardwallet$->{user_id % 2 + 1}
```

Since both `users.id` and `wallet.user_id` use the same formula (`key % 2 + 1`), they always map to the same shard.

### Saga Step Co-location

```yaml
# Saga instance sharded by id
saga_instance:
  shardingColumn: id
  shardingAlgorithmName: db-inline      # id % 2 + 1

# Saga steps sharded by saga_instance_id
saga_step:
  shardingColumn: saga_instance_id
  shardingAlgorithmName: saga-inline    # saga_instance_id % 2 + 1
```

Result: A saga instance and ALL its steps are on the same shard. "Get all steps for saga X" is always a single-shard query.

---

## 3. Benefits

| Benefit | Without Co-location | With Co-location |
|---------|-------------------|-----------------|
| **"Get user's wallets"** | Scatter-gather (all shards) | Single-shard query |
| **JOIN user + wallet** | Cross-shard join (slow/impossible) | Local join |
| **User + wallet transaction** | Distributed transaction needed | Local ACID |
| **Network round trips** | N (one per shard) | 1 |
| **Query latency** | High (multi-shard) | Low (single-shard) |

---

## 4. Trade-offs

| Pro | Con |
|-----|-----|
| Fast single-shard queries for related data | Shard key is fixed — can't change easily |
| Local ACID for related entities | "Get all wallets" (no user_id) → scatter-gather |
| No cross-shard joins needed for common queries | Uneven distribution if some users have many wallets |
| Simpler transaction management | Cross-user queries (e.g., "all active wallets") still hit all shards |

---

## 5. Co-location Design Patterns

### Pattern: Entity Group

Group related entities by a common ancestor key:

```
Ancestor: User (id)
  └── Wallet (user_id = user.id)        ← co-located
  └── UserPreferences (user_id)          ← co-located
  └── UserNotifications (user_id)        ← co-located

All entities in the group share the same shard key derivation.
```

### When NOT to Co-locate

Sometimes entities shouldn't be co-located:

```
Transactions table:
  - Has source_wallet_id AND destination_wallet_id
  - Source user might be on shard 1, destination on shard 2
  - Can't co-locate with BOTH users
  - Decision: shard by transaction.id (independent sharding)
  - Trade-off: "Get user's transactions" requires scatter-gather
```

---

## 6. Interview Quick-Fire

**Q: What is data co-location and why does it matter?**
A: Placing related entities on the same database shard so queries involving them are local (single-shard), avoiding expensive cross-shard joins and enabling local ACID transactions.

**Q: Why did you shard wallets by user_id instead of wallet.id?**
A: So that a user and all their wallets are on the same shard. This makes "get user's wallets" a single-shard query and enables local ACID when updating a user and their wallet together.

**Q: What's the downside of co-location?**
A: Queries without the shard key (e.g., "find all wallets with balance > 1000") must scatter-gather across all shards. You're optimizing for specific access patterns at the cost of others.

---

## Key Takeaway

> We co-locate users and wallets on the same shard by using `user_id` as the wallet's shard key (same formula as `users.id`). Similarly, saga steps are co-located with their saga instance. This eliminates cross-shard queries for the most common access patterns while accepting scatter-gather for rarer queries.
