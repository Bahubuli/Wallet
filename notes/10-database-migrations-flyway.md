# Database Migrations with Flyway

## Quick Summary

**Flyway** is a database migration tool that applies **versioned SQL scripts** to your database in order, tracking which migrations have been applied. It ensures your database schema evolves consistently across all environments (dev, staging, production).

---

## 1. Why Migrations?

### Without Migrations (Chaos)

```
Developer A: "I added a currency column to wallet table"
Developer B: "Wait, I added it too but with a different type"
Production:  "Neither column exists — who ran the ALTER TABLE?"
Staging:     "Has currency column but not the index"
```

### With Flyway (Order)

```
V1__Initial_Schema.sql         ← Everyone runs this first
V2__Add_Wallet_Enhancements.sql ← Then this, exactly once
V3__Add_Transaction_Index.sql  ← Then this, in order

Every environment has the same schema at the same version.
Flyway tracks what's been applied in flyway_schema_history table.
```

---

## 2. How Flyway Works

```
┌─────────────────────────────────────────┐
│            Application Startup           │
│                                         │
│  1. Flyway reads migration folder:      │
│     db/migration/                        │
│     ├── V1__Initial_Schema.sql          │
│     ├── V2__Add_Wallet_Enhancements.sql │
│     └── V3__Future_Migration.sql        │
│                                         │
│  2. Checks flyway_schema_history table: │
│     ┌─────────┬────────────────────┐    │
│     │ version │ script             │    │
│     ├─────────┼────────────────────┤    │
│     │ 1       │ V1__Initial_Schema │    │
│     │ 2       │ V2__Add_Wallet_... │    │
│     └─────────┴────────────────────┘    │
│                                         │
│  3. V1 and V2 already applied → skip    │
│  4. V3 is new → execute it!             │
│  5. Record V3 in flyway_schema_history  │
└─────────────────────────────────────────┘
```

### Naming Convention

```
V{version}__{description}.sql
│    │     │       │
│    │     │       └── Human-readable description (underscores = spaces)
│    │     └────────── Double underscore separator (required)
│    └──────────────── Version number (1, 2, 3, ... or 1.1, 1.2)
└───────────────────── V = Versioned migration (applied once)

Other prefixes:
  R__{description}.sql  → Repeatable (re-applied when checksum changes)
  U{version}__{description}.sql → Undo migration (Flyway Teams only)
```

---

## 3. Our Migration Files

### V1__Initial_Schema.sql

```sql
-- Creates all 5 core tables
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255),
    email VARCHAR(255)
);

CREATE TABLE IF NOT EXISTS wallet (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    is_active BOOLEAN NOT NULL,
    balance DECIMAL(19,2) NOT NULL DEFAULT 0.00
);

CREATE TABLE IF NOT EXISTS saga_instance (
    id BIGSERIAL PRIMARY KEY,
    saga_type VARCHAR(100) NOT NULL,
    status VARCHAR(50) NOT NULL,
    context JSONB NOT NULL,
    current_step VARCHAR(255) NOT NULL,
    -- ... more columns
    version BIGINT,
    created_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS saga_step (
    id BIGSERIAL PRIMARY KEY,
    saga_instance_id BIGINT NOT NULL,
    step_order INTEGER NOT NULL,
    step_name VARCHAR(100) NOT NULL,
    status VARCHAR(50) NOT NULL,
    -- ... more columns
    CONSTRAINT fk_saga_step_instance 
        FOREIGN KEY (saga_instance_id) REFERENCES saga_instance(id)
);

CREATE TABLE IF NOT EXISTS transactions (
    id BIGSERIAL PRIMARY KEY,
    description VARCHAR(255) NOT NULL,
    source_wallet_id BIGINT NOT NULL,
    destination_wallet_id BIGINT NOT NULL,
    amount DECIMAL(19,2) NOT NULL,
    status VARCHAR(50) NOT NULL,
    type VARCHAR(50) NOT NULL,
    saga_instance_id BIGINT NOT NULL,
    created_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_date TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_saga_status_created ON saga_instance(status, created_date);
CREATE INDEX IF NOT EXISTS idx_saga_type_status ON saga_instance(saga_type, status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_saga_step_order ON saga_step(saga_instance_id, step_order);
```

### V2__Add_Wallet_Enhancements.sql

```sql
-- Non-destructive ALTER TABLE additions
ALTER TABLE wallet ADD COLUMN IF NOT EXISTS currency VARCHAR(3) NOT NULL DEFAULT 'USD';
ALTER TABLE wallet ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0;
ALTER TABLE wallet ADD COLUMN IF NOT EXISTS created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE wallet ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;
```

---

## 4. Special Challenge: Flyway + Sharding

### The Problem

```
ShardingSphere routes SQL based on shard keys.
Flyway's CREATE TABLE has no shard key.
If we let Flyway run through ShardingSphere:
  → CREATE TABLE users → ShardingSphere doesn't know which shard → undefined behavior
  → Table only created on one shard, not both!
```

### Our Solution: Run Flyway Directly on Each Shard

```java
// DataSourceConfig.java
@Bean
public DataSource dataSource() throws Exception {
    String url1 = System.getProperty("POSTGRES_DB1_URL");
    String url2 = System.getProperty("POSTGRES_DB2_URL");

    // Run Flyway DIRECTLY on each physical database (bypass ShardingSphere)
    for (String url : new String[]{url1, url2}) {
        Flyway.configure()
            .dataSource(url, postgresUser, postgresPass)
            .baselineOnMigrate(true)    // Don't fail if DB already has tables
            .baselineVersion("1")        // Start from version 1
            .locations("classpath:db/migration")
            .load()
            .migrate();
    }

    // THEN create ShardingSphere DataSource (tables already exist on both shards)
    return YamlShardingSphereDataSourceFactory.createDataSource(tempYaml.toFile());
}
```

```
Startup sequence:
  1. Flyway → shardwallet1 (CREATE TABLE users, wallet, ...)
  2. Flyway → shardwallet2 (CREATE TABLE users, wallet, ...)
  3. ShardingSphere DataSource created (tables ready on both shards)
  4. Application ready
```

### Configuration

```properties
# application.properties
spring.flyway.enabled=false   # ← Disable Spring Boot's auto Flyway
                               #   We run it manually in DataSourceConfig
spring.jpa.hibernate.ddl-auto=none  # ← Hibernate doesn't touch schema
```

---

## 5. Flyway Best Practices

### DO

| Practice | Why |
|----------|-----|
| Use `IF NOT EXISTS` / `IF EXISTS` | Safe re-runs, idempotent DDL |
| One change per migration | Easy to identify what each version does |
| Never modify applied migrations | Checksum mismatch → Flyway fails |
| Test migrations on copy of production DB | Catch issues before deployment |
| Use DEFAULT values for new NOT NULL columns | Existing rows need values |

### DON'T

| Anti-Pattern | Why It's Bad |
|-------------|-------------|
| Editing V1 after it's been applied | Checksum changes → Flyway refuses to run |
| Dropping columns in same migration as adding | If migration fails halfway, inconsistent state |
| Long-running migrations without timeout | May lock tables for minutes |
| Data migrations mixed with schema migrations | Harder to rollback; test separately |

---

## 6. Migration Patterns

### Add Column (Non-Breaking)

```sql
-- V3__Add_wallet_type.sql
ALTER TABLE wallet ADD COLUMN IF NOT EXISTS wallet_type VARCHAR(20) DEFAULT 'PERSONAL';
```

### Add Index (Non-Breaking)

```sql
-- V4__Add_transaction_indexes.sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_txn_source 
    ON transactions(source_wallet_id);
-- CONCURRENTLY = doesn't lock table (PostgreSQL)
```

### Rename Column (Breaking — Needs Strategy)

```sql
-- Phase 1: Add new column, copy data
ALTER TABLE wallet ADD COLUMN IF NOT EXISTS wallet_name VARCHAR(255);
UPDATE wallet SET wallet_name = name WHERE wallet_name IS NULL;

-- Phase 2 (next release): Drop old column after app no longer uses it
ALTER TABLE wallet DROP COLUMN IF EXISTS name;
```

---

## 7. Interview Quick-Fire

**Q: Why use Flyway instead of Hibernate's ddl-auto?**
A: `ddl-auto=update` is dangerous in production — Hibernate may make unexpected changes. Flyway gives explicit, versioned, reviewable SQL migrations that can be tested before deployment.

**Q: What happens if a migration fails halfway?**
A: PostgreSQL wraps each migration in a transaction. If it fails, the whole migration is rolled back. Flyway marks it as failed, and you must fix the SQL and re-run (or use `flyway repair`).

**Q: How do you handle Flyway with ShardingSphere?**
A: We disable Spring's auto-Flyway and run migrations manually against each physical shard before creating the ShardingSphere DataSource. This ensures identical schema on all shards.

**Q: What is `baselineOnMigrate`?**
A: It tells Flyway that if a database already has tables but no `flyway_schema_history`, treat the current state as the baseline (version 1) and start tracking from there. Useful for adopting Flyway on existing databases.

---

## Key Takeaway

> We use **Flyway** for versioned, repeatable database migrations. The key challenge is running migrations on **each physical shard independently** before ShardingSphere initializes, since ShardingSphere would route DDL statements unpredictably. We solve this by running Flyway programmatically in `DataSourceConfig`, disabling Spring Boot's auto-Flyway, and setting `ddl-auto=none`.
