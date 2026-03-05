# JSONB in PostgreSQL & Advanced Column Mapping

## Quick Summary

**JSONB** = PostgreSQL binary JSON storage format — stores structured, schema-less data inside a relational column with full indexing and query support.
**Use case in our project**: `SagaInstance.context` and `SagaStep.stepData` store dynamic key-value data as JSONB, avoiding rigid column definitions for data that varies per saga type.

---

## 1. JSON vs JSONB in PostgreSQL

| Feature | JSON | JSONB |
|---------|------|-------|
| Storage | Raw text (preserves whitespace, key order) | Parsed binary (compact, no duplicates) |
| Write speed | Faster (no parsing overhead) | Slightly slower (must parse) |
| Read/Query speed | Slower (re-parses every query) | **Much faster** (pre-parsed binary) |
| Indexing | ❌ No GIN/GiST index | ✅ GIN index support |
| Operators | Basic | Full (`@>`, `?`, `?|`, `#>>`, `->`, `->>`, `jsonb_path_query`) |
| Duplicate keys | Preserved | Last value wins |
| **Verdict** | Logging / audit trails | **Almost always use JSONB** |

---

## 2. Our JSONB Usage

### SagaInstance — Dynamic Context

```java
@Entity
@Table(name = "saga_instances")
public class SagaInstance {
    
    @JdbcTypeCode(SqlTypes.JSON)                     // Tells Hibernate: serialize as JSON
    @Column(columnDefinition = "jsonb")              // DDL hint: PostgreSQL JSONB column
    @ColumnTransformer(                               // SQL-level type casting
        read = "context",
        write = "?::jsonb"                            // Cast Java string → PostgreSQL jsonb
    )
    private Map<String, Object> context;              // Java type: flexible map
}
```

### SagaStep — Step-specific Data

```java
@Entity
@Table(name = "saga_steps")
public class SagaStep {
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    @ColumnTransformer(read = "step_data", write = "?::jsonb")
    private Map<String, Object> stepData;
}
```

### What Gets Stored

```json
// SagaInstance.context — shared data across all saga steps
{
  "sourceWalletId": 1001,
  "destinationWalletId": 2002,
  "amount": 500.00,
  "transactionId": 98765,
  "currency": "USD"
}

// SagaStep.stepData — step-specific data (e.g., compensation info)
{
  "previousBalance": 1500.00,
  "debitedAmount": 500.00,
  "walletId": 1001
}
```

---

## 3. Three Annotations Explained

### @JdbcTypeCode(SqlTypes.JSON)

```java
@JdbcTypeCode(SqlTypes.JSON)
private Map<String, Object> context;
```
- **What**: Tells Hibernate 6+ to use the JSON JDBC type code for serialization
- **Effect**: Hibernate serializes the `Map` to a JSON string when writing to DB and deserializes JSON string back to `Map` when reading
- **Hibernate 5 equivalent**: `@Type(type = "jsonb")` with `hibernate-types` library

### @Column(columnDefinition = "jsonb")

```java
@Column(columnDefinition = "jsonb")
```
- **What**: DDL generation hint — if Hibernate generates the schema, it creates a `jsonb` column (not `varchar`)
- **Effect**: Only matters for `spring.jpa.hibernate.ddl-auto=create/update`
- **Our case**: We use Flyway for DDL, so this is a documentation hint

### @ColumnTransformer(write = "?::jsonb")

```java
@ColumnTransformer(read = "context", write = "?::jsonb")
```
- **What**: Modifies the SQL that Hibernate generates for reads/writes
- **Effect on write**: `INSERT INTO ... VALUES (?::jsonb)` — casts the string parameter to `jsonb` type
- **Why needed**: PostgreSQL may reject plain text insertion into a `jsonb` column without explicit cast
- **Effect on read**: `SELECT context FROM ...` — direct read (no transformation needed)

### All Three Together

```
Java Map<String, Object>
    │
    │  @JdbcTypeCode(SqlTypes.JSON) — serialize Map → JSON string
    ▼
JSON String: '{"sourceWalletId": 1001, "amount": 500}'
    │
    │  @ColumnTransformer(write = "?::jsonb") — cast string → jsonb
    ▼
PostgreSQL JSONB column: {"sourceWalletId": 1001, "amount": 500}
```

---

## 4. DDL (SQL Migration)

```sql
-- V1__Initial_Schema.sql
CREATE TABLE saga_instances (
    id              BIGINT PRIMARY KEY,
    saga_type       VARCHAR(50)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'STARTED',
    context         JSONB,                    -- ← JSONB column
    current_step    INTEGER      NOT NULL DEFAULT 0,
    max_retries     INTEGER      NOT NULL DEFAULT 3,
    retry_count     INTEGER      NOT NULL DEFAULT 0,
    -- ...
);

CREATE TABLE saga_steps (
    id               BIGINT PRIMARY KEY,
    saga_instance_id BIGINT  NOT NULL,
    step_name        VARCHAR(100) NOT NULL,
    step_order       INTEGER      NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    step_data        JSONB,                   -- ← JSONB column
    error_message    TEXT,
    -- ...
);
```

---

## 5. PostgreSQL JSONB Operators

### Querying JSONB Data

```sql
-- Access nested value as JSON element (returns JSON)
SELECT context->'amount' FROM saga_instances;
-- Result: 500.00 (as JSON)

-- Access nested value as text (returns text)
SELECT context->>'sourceWalletId' FROM saga_instances;
-- Result: "1001" (as text string)

-- Deep nested access
SELECT context#>>'{address,city}' FROM saga_instances;

-- Check if JSONB contains a key-value pair
SELECT * FROM saga_instances WHERE context @> '{"status": "COMPLETED"}';

-- Check if JSONB has a specific key
SELECT * FROM saga_instances WHERE context ? 'amount';

-- Check if JSONB has ANY of these keys
SELECT * FROM saga_instances WHERE context ?| ARRAY['amount', 'currency'];
```

### Operator Reference

| Operator | Description | Example |
|----------|------------|---------|
| `->` | Get JSON element by key | `context->'amount'` → `500.00` (JSON) |
| `->>` | Get JSON element as text | `context->>'amount'` → `"500.00"` (text) |
| `#>` | Get nested JSON by path | `context#>'{a,b}'` → JSON |
| `#>>` | Get nested text by path | `context#>>'{a,b}'` → text |
| `@>` | Contains (left contains right) | `context @> '{"key":"val"}'` → boolean |
| `<@` | Contained by | `'{"key":"val"}' <@ context` → boolean |
| `?` | Has key | `context ? 'amount'` → boolean |
| `?&` | Has all keys | `context ?& ARRAY['a','b']` |
| `?|` | Has any key | `context ?| ARRAY['a','b']` |
| `\|\|` | Concatenate JSONB | `context \|\| '{"new":"val"}'` |
| `-` | Delete key | `context - 'oldKey'` |

---

## 6. Indexing JSONB

```sql
-- GIN index on entire JSONB column (supports @>, ?, ?|, ?& operators)
CREATE INDEX idx_saga_context ON saga_instances USING GIN (context);

-- GIN index with jsonb_path_ops (supports only @>, smaller and faster)
CREATE INDEX idx_saga_context_path ON saga_instances USING GIN (context jsonb_path_ops);

-- B-Tree index on specific JSONB field (for equality/range queries)
CREATE INDEX idx_saga_amount ON saga_instances ((context->>'amount'));

-- Functional index for typed queries
CREATE INDEX idx_saga_source ON saga_instances (((context->>'sourceWalletId')::bigint));
```

---

## 7. When to Use JSONB vs Relational Columns

```
USE JSONB when:
  ✅ Schema varies by record type (saga types have different context data)
  ✅ Data is accessed as a unit, not frequently queried by individual fields
  ✅ Metadata, configuration, or audit data
  ✅ You need schema flexibility without migrations
  ✅ Nested structures that don't fit relational model well

USE REGULAR COLUMNS when:
  ✅ Field is frequently used in WHERE, JOIN, ORDER BY
  ✅ Field needs foreign key constraints
  ✅ Field needs strict type enforcement
  ✅ Field is part of core domain model (e.g., balance, userId)
  ✅ You need database-level NOT NULL / CHECK constraints
```

### Our Design Decision

```
SagaInstance.context = JSONB
  → Different saga types (TRANSFER, DEPOSIT, WITHDRAWAL) need different context fields
  → Transfer needs: sourceWalletId, destinationWalletId, amount
  → Future deposit saga needs: walletId, externalRef, provider
  → Adding a new saga type requires NO schema migration for context

Wallet.balance = DECIMAL COLUMN
  → Queried constantly (WHERE, ORDER BY, aggregate SUM)
  → Needs CHECK constraint (balance >= 0)
  → Needs precision guarantees (DECIMAL(19,4))
  → Part of core domain model
```

---

## 8. Trade-offs

| Aspect | JSONB | Relational |
|--------|-------|------------|
| Schema flexibility | ✅ No migration needed | ❌ ALTER TABLE + migration |
| Query performance | ⚠️ Good with GIN index | ✅ B-Tree native |
| Type safety | ❌ Runtime (app must validate) | ✅ Database enforces |
| Foreign keys | ❌ Not possible on JSONB fields | ✅ Full referential integrity |
| Null handling | ❌ Missing key = no error | ✅ NOT NULL constraint |
| Tooling | ⚠️ Some ORMs struggle | ✅ Full ORM support |
| Storage efficiency | ✅ Compact for sparse data | ❌ NULL columns waste space |
| JOINs | ❌ Expensive on JSONB fields | ✅ Optimized by planner |

---

## 9. Interview Quick-Fire

**Q: Why JSONB over JSON in PostgreSQL?**
A: JSONB stores data in decomposed binary format, supports GIN indexing, and provides faster read/query performance. JSON stores raw text, re-parses every query, and can't be indexed.

**Q: When would you choose JSONB over adding new columns?**
A: When the data structure varies by record type (like saga context per saga type), is accessed as a unit, or changes frequently — JSONB avoids constant schema migrations while keeping everything in one table.

**Q: What's the role of @ColumnTransformer(write = "?::jsonb")?**
A: It injects a PostgreSQL type cast into the SQL INSERT/UPDATE statement, ensuring the Java string is explicitly cast to the `jsonb` type. Without it, PostgreSQL may reject the insert due to type mismatch.

**Q: Can you index individual fields inside JSONB?**
A: Yes — use expression indexes: `CREATE INDEX idx ON table ((col->>'field'))` for B-Tree indexes on specific fields, or GIN indexes on the entire column for containment queries.

---

## Key Takeaway

> We use **PostgreSQL JSONB** for `SagaInstance.context` and `SagaStep.stepData` because different saga types need different context fields. The combination of `@JdbcTypeCode(SqlTypes.JSON)` + `@ColumnTransformer(write = "?::jsonb")` handles Java Map ↔ JSONB serialization. Core domain fields (balance, userId) remain as relational columns for type safety, indexing, and constraints.
