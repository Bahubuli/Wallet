# Wallet — Distributed Digital Wallet System

A **production-grade digital wallet backend** built with **Spring Boot 4.0.1** and **Java 21**, featuring wallet-to-wallet fund transfers across **sharded PostgreSQL databases** using the **Saga Orchestration Pattern**. Designed to solve the distributed transaction problem that arises when data is partitioned across multiple database shards.

---

## Table of Contents

- [Why This Project?](#why-this-project)
- [Features](#features)
- [Architecture](#architecture)
  - [System Overview](#system-overview)
  - [Sharding Strategy](#sharding-strategy)
  - [Saga Orchestration Flow](#saga-orchestration-flow)
- [Tech Stack](#tech-stack)
- [Project Structure](#project-structure)
- [Getting Started](#getting-started)
  - [Prerequisites](#prerequisites)
  - [Database Setup](#database-setup)
  - [Environment Configuration](#environment-configuration)
  - [Build & Run](#build--run)
  - [Verify the Setup](#verify-the-setup)
- [API Reference](#api-reference)
  - [User Endpoints](#user-endpoints)
  - [Wallet Endpoints](#wallet-endpoints)
  - [Transaction Endpoints](#transaction-endpoints)
- [End-to-End Transfer Example](#end-to-end-transfer-example)
- [Database Schema](#database-schema)
- [How the Saga Pattern Works](#how-the-saga-pattern-works)
- [Resilience & Retry Mechanism](#resilience--retry-mechanism)
- [Configuration Reference](#configuration-reference)
- [Troubleshooting](#troubleshooting)
- [Upcoming Features](#upcoming-features)

---

## Why This Project?

When data is distributed across multiple database shards, **traditional ACID transactions cannot span them**. A single `BEGIN → COMMIT` can't wrap operations on two independent PostgreSQL instances. This project solves that problem by implementing the **Saga Orchestration Pattern** — breaking a distributed transaction into a sequence of local ACID transactions, each with a **compensating action** to undo its effects if a later step fails.

**Key problem solved:** Safely transferring funds between wallets that may reside on different database shards, with automatic rollback (compensation) on failure.

---

## Features

### Core
- **User Management** — Create users, search by name/ID, auto-sharded across databases
- **Wallet Management** — Create wallets, activate/deactivate, add funds, check balance, with optimistic locking (`@Version`)
- **Fund Transfers** — Wallet-to-wallet transfers orchestrated via the Saga pattern with full compensation support
- **Rich Transaction Query API** — Filter transactions by wallet, source, destination, status, saga instance, and more; all paginated

### Distributed Systems
- **Saga Orchestration Engine** — Full forward execution + reverse compensation with persistent state tracking
- **Database Sharding** — Transparent SQL routing across 2 PostgreSQL shards via Apache ShardingSphere JDBC
- **Snowflake ID Generation** — Globally unique, time-sortable IDs across all shards (no ID collisions)
- **Data Co-location** — Wallets sharded by `user_id` to co-locate user and wallet data on the same shard

### Resilience
- **Automatic Retry with Exponential Backoff** — Spring RetryTemplate handles transient failures (optimistic lock conflicts, lock acquisition failures, transient DB errors)
- **Compensating Transactions** — Completed saga steps are compensated in reverse order on failure
- **Optimistic Locking** — `@Version` on Wallet, SagaInstance, and SagaStep entities prevents concurrent modification conflicts
- **Per-Step Retry Tracking** — Each saga step tracks its own retry count against a configurable max

### Data & Operations
- **Flyway Database Migrations** — Schema versioning with migrations run directly on each physical shard before ShardingSphere initialization
- **JSONB Context Storage** — Saga execution context serialized as JSON in PostgreSQL JSONB columns for full step-to-step data passing
- **Comprehensive Audit Timestamps** — `created_date`, `updated_date`, `started_date`, `completed_date` across all entities
- **Global Exception Handling** — `@RestControllerAdvice` with structured error responses for validation, business, and not-found errors
- **Input Validation** — Bean Validation (`@NotNull`, `@Positive`, `@Email`, `@DecimalMin`) on all request DTOs

---

## Architecture

### System Overview

```
┌──────────────────────────────────────────────────────────────┐
│                       REST Controllers                        │
│  UserController │ WalletController │ TransactionController     │
└───────┬─────────────────┬──────────────────┬─────────────────┘
        │                 │                  │
┌───────▼─────────────────▼──────────────────▼─────────────────┐
│                        Services                               │
│     UserService  │  WalletService  │  TransactionService      │
└──────────────────────────┬───────────────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────────────┐
│                  Saga Orchestration Layer                      │
│  TransferSagaService → SagaOrchestrator (SagaOrchestratorImpl)│
│           ↓                                                   │
│  SagaStepFactory → [DebitSourceWallet, CreditDestination,     │
│                      UpdateTransactionStatus]                 │
│  SagaContext (serialized as JSON in DB)                       │
└──────────────────────────┬───────────────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────────────┐
│                     Repositories (JPA)                         │
│  UserRepo │ WalletRepo │ TransactionRepo │ SagaInstanceRepo  │
│                                          │ SagaStepRepo      │
└──────────────────────────┬───────────────────────────────────┘
                           │
┌──────────────────────────▼───────────────────────────────────┐
│              ShardingSphere JDBC DataSource                    │
│  ┌─────────────────┐          ┌─────────────────┐            │
│  │  shardwallet1   │          │  shardwallet2   │            │
│  │  (PostgreSQL)   │          │  (PostgreSQL)   │            │
│  └─────────────────┘          └─────────────────┘            │
└──────────────────────────────────────────────────────────────┘
```

### Sharding Strategy

| Table            | Shard Key            | Algorithm                           |
|------------------|----------------------|-------------------------------------|
| `users`          | `id`                 | `id % 2 + 1` → shardwallet1 or 2   |
| `wallet`         | `user_id`            | `user_id % 2 + 1`                  |
| `transactions`   | `id`                 | `id % 2 + 1`                       |
| `saga_instance`  | `id`                 | `id % 2 + 1`                       |
| `saga_step`      | `saga_instance_id`   | `saga_instance_id % 2 + 1`         |

**Design decision:** Wallets are sharded by `user_id` (not `id`) to **co-locate a user's wallets on the same shard as the user**, enabling efficient joins and queries.

### Saga Orchestration Flow

```
1. Create Transaction (PENDING) + Create SagaInstance (STARTED)
   │
2. Execute DEBIT_SOURCE_WALLET step
   │  ✓ Debit source wallet balance
   │
3. Execute CREDIT_DESTINATION_WALLET step
   │  ✓ Credit destination wallet balance
   │
4. Execute UPDATE_TRANSACTION_STATUS step
   │  ✓ Mark transaction as SUCCESS
   │
5. Complete Saga (COMPLETED)

If any step fails:
   → Compensate all completed steps in REVERSE order
   → Mark saga as COMPENSATED (or FAILED if compensation fails)
```

**Saga Statuses:** `STARTED` → `RUNNING` → `COMPLETED` | `COMPENSATING` → `COMPENSATED` | `FAILED`

**Step Statuses:** `PENDING` → `RUNNING` → `COMPLETED` | `COMPENSATING` → `COMPENSATED` | `FAILED` | `SKIPPED`

---

## Tech Stack

| Component              | Technology                                         |
|------------------------|----------------------------------------------------|
| **Language**           | Java 21                                            |
| **Framework**          | Spring Boot 4.0.1                                  |
| **Build Tool**         | Gradle (Groovy DSL) with Wrapper                   |
| **ORM**                | Spring Data JPA / Hibernate                        |
| **Database**           | PostgreSQL (2 shards: shardwallet1, shardwallet2)  |
| **Sharding**           | Apache ShardingSphere JDBC 5.5.2                   |
| **Migrations**         | Flyway                                             |
| **Retry**              | Spring Retry 2.0.11 (programmatic RetryTemplate)   |
| **ID Generation**      | Snowflake (via ShardingSphere)                     |
| **Validation**         | Jakarta Bean Validation (Hibernate Validator)      |
| **Boilerplate**        | Lombok                                             |
| **Env Config**         | dotenv-java 3.0.0                                  |
| **Serialization**      | Jackson (ObjectMapper)                             |
| **Testing**            | JUnit 5 (via Spring Boot Test)                     |

---

## Project Structure

```
Wallet/
├── build.gradle                            # Dependencies & build configuration
├── settings.gradle                         # Gradle project settings
├── gradlew / gradlew.bat                   # Gradle Wrapper (no Gradle install needed)
├── .env                                    # Environment variables (create this)
├── README.md                               # You are here
├── docs/                                   # Detailed documentation
│
├── src/main/java/com/jitendra/Wallet/
│   ├── WalletApplication.java              # Entry point — loads .env, sets system props
│   │
│   ├── config/
│   │   └── DataSourceConfig.java           # ShardingSphere + Flyway programmatic config
│   │
│   ├── controller/
│   │   ├── UserController.java             # User REST endpoints
│   │   ├── WalletController.java           # Wallet REST endpoints (paginated)
│   │   └── TransactionController.java      # Transaction REST endpoints (paginated)
│   │
│   ├── dto/
│   │   ├── UserRequestDTO / ResponseDTO    # User data transfer objects
│   │   ├── WalletRequestDTO / ResponseDTO  # Wallet DTOs with validation
│   │   ├── TransactionRequestDTO / ResponseDTO
│   │   └── ErrorResponseDTO               # Structured error response
│   │
│   ├── entity/
│   │   ├── User.java                       # User entity (sharded by id)
│   │   ├── Wallet.java                     # Wallet with optimistic lock + business methods
│   │   ├── Transaction.java               # Transaction with saga linkage
│   │   ├── SagaInstance.java              # Saga state tracker (JSONB context)
│   │   ├── SagaStep.java                  # Individual step state tracker
│   │   └── Enums: SagaStatus, StepStatus, TransactionStatus, TransactionType
│   │
│   ├── exception/
│   │   ├── GlobalExceptionHandler.java     # @RestControllerAdvice — centralized errors
│   │   ├── BusinessException.java          # Business rule violations
│   │   └── ResourceNotFoundException.java  # 404 errors
│   │
│   ├── repository/
│   │   ├── UserRepository.java
│   │   ├── WalletRepository.java           # Paginated + list queries
│   │   ├── TransactionRepository.java      # Rich query interface (15+ methods)
│   │   ├── SagaInstanceRepository.java     # Expiry, retry eligibility queries
│   │   └── SagaStepRepository.java         # Step status tracking queries
│   │
│   └── services/
│       ├── UserService.java
│       ├── WalletService.java              # Debit/credit with validation
│       ├── TransactionService.java         # Delegates transfers to saga
│       └── saga/
│           ├── SagaOrchestrator.java       # Interface: start, execute, compensate
│           ├── SagaOrchestratorImpl.java   # Full implementation with retry + compensation
│           ├── SagaContext.java            # Portable context (JSON serializable)
│           ├── SagaStepInterface.java      # Step contract: execute + compensate
│           ├── TransferSagaService.java    # Transfer-specific saga coordinator
│           └── steps/
│               ├── SagaStepFactory.java    # Step registry + saga type definitions
│               ├── DebitSourceWalletStep.java
│               ├── CreditDestinationWalletStep.java
│               └── UpdateTransactionStatus.java
│
├── src/main/resources/
│   ├── application.properties              # Spring Boot configuration
│   ├── sharding.yml                        # ShardingSphere sharding rules
│   └── db/migration/
│       ├── V1__Initial_Schema.sql          # Tables, indexes, constraints
│       └── V2__Add_Wallet_Enhancements.sql # Currency, version, timestamps
│
└── src/test/java/                          # Test suite
```

---

## Getting Started

### Prerequisites

| Requirement       | Version         | Notes                              |
|-------------------|-----------------|------------------------------------|
| **Java JDK**      | 21 or higher    | [Download](https://adoptium.net/)  |
| **PostgreSQL**    | 14 or higher    | [Download](https://www.postgresql.org/download/) |
| **Git**           | Any recent      | [Download](https://git-scm.com/)   |

> You do **not** need to install Gradle. The project includes a Gradle Wrapper.

### Database Setup

The application requires **two** PostgreSQL databases for sharding:

```bash
# Connect to PostgreSQL
psql -U postgres
```

```sql
CREATE DATABASE shardwallet1;
CREATE DATABASE shardwallet2;
\q
```

> Database names must be exactly `shardwallet1` and `shardwallet2`.

### Environment Configuration

Create a `.env` file in the project root:

```env
POSTGRES_USER=postgres
POSTGRES_PASS=your_postgres_password
POSTGRES_DB1_URL=jdbc:postgresql://localhost:5432/shardwallet1
POSTGRES_DB2_URL=jdbc:postgresql://localhost:5432/shardwallet2
```

**Default fallbacks** (if no `.env` provided): `postgres` / `admin` / `localhost:5432/wallet1` / `localhost:5432/wallet2`

### Build & Run

```bash
# Windows
.\gradlew.bat bootRun

# Linux/macOS
chmod +x gradlew
./gradlew bootRun
```

The application starts on **http://localhost:8080**.

**Build only:**
```bash
.\gradlew.bat compileJava     # Compile
.\gradlew.bat build -x test   # Full build without tests
.\gradlew.bat bootJar         # Build executable JAR
```

### Verify the Setup

```bash
curl -X POST http://localhost:8080/users/create \
  -H "Content-Type: application/json" \
  -d '{"name": "Test User", "email": "test@example.com"}'
```

Expected: A JSON response with a Snowflake-generated ID (large number like `928586788773888001`).

---

## API Reference

### User Endpoints

| Method | Endpoint                    | Description       |
|--------|-----------------------------|-------------------|
| POST   | `/users/create`             | Create a user     |
| GET    | `/users/{id}`               | Get user by ID    |
| GET    | `/users/search?name=`       | Search by name    |

### Wallet Endpoints

| Method | Endpoint                             | Description          |
|--------|--------------------------------------|----------------------|
| POST   | `/wallets/create`                    | Create wallet        |
| GET    | `/wallets/{id}`                      | Get wallet by ID     |
| GET    | `/wallets/user/{userId}`             | Get user's wallets (paginated) |
| PUT    | `/wallets/{id}/activate`             | Activate wallet      |
| PUT    | `/wallets/{id}/deactivate`           | Deactivate wallet    |
| GET    | `/wallets/{id}/balance`              | Get balance          |
| POST   | `/wallets/{id}/add-funds?amount=100` | Add funds            |

### Transaction Endpoints

| Method | Endpoint                                                        | Description                    |
|--------|-----------------------------------------------------------------|--------------------------------|
| POST   | `/transactions/create`                                          | Create transfer (triggers saga)|
| GET    | `/transactions/{id}`                                            | Get by ID                      |
| GET    | `/transactions/wallet/{walletId}`                               | By wallet (paginated)          |
| GET    | `/transactions/source/{sourceWalletId}`                         | By source wallet               |
| GET    | `/transactions/destination/{destId}`                            | By destination wallet          |
| GET    | `/transactions/status?status=PENDING`                           | By status                      |
| GET    | `/transactions/saga/{sagaInstanceId}`                           | By saga instance               |
| PUT    | `/transactions/{id}/status?status=SUCCESS`                      | Update status                  |
| GET    | `/transactions/between?sourceWalletId=1&destinationWalletId=2`  | Between two wallets            |
| GET    | `/transactions/saga/{id}/pending`                               | Pending for saga               |
| GET    | `/transactions/wallet/{id}/successful`                          | Successful for wallet          |
| GET    | `/transactions/wallet/{id}/failed`                              | Failed for wallet              |

**Create Transaction Body:**
```json
{
  "description": "Monthly rent payment",
  "sourceWalletId": 928586788773888001,
  "destinationWalletId": 928586788773888002,
  "amount": 500.00,
  "type": "TRANSFER"
}
```

---

## End-to-End Transfer Example

```bash
# 1. Create two users
curl -s -X POST http://localhost:8080/users/create \
  -H "Content-Type: application/json" \
  -d '{"name": "Alice", "email": "alice@example.com"}'
# → {"id": 928586788773888001, ...}

curl -s -X POST http://localhost:8080/users/create \
  -H "Content-Type: application/json" \
  -d '{"name": "Bob", "email": "bob@example.com"}'
# → {"id": 928586788773888002, ...}

# 2. Create wallets (use actual IDs from step 1)
curl -s -X POST http://localhost:8080/wallets/create \
  -H "Content-Type: application/json" \
  -d '{"userId": 928586788773888001, "isActive": true, "balance": 0}'

curl -s -X POST http://localhost:8080/wallets/create \
  -H "Content-Type: application/json" \
  -d '{"userId": 928586788773888002, "isActive": true, "balance": 0}'

# 3. Fund Alice's wallet
curl -s -X POST "http://localhost:8080/wallets/{aliceWalletId}/add-funds?amount=1000.00"

# 4. Transfer $250 from Alice to Bob (triggers Saga)
curl -s -X POST http://localhost:8080/transactions/create \
  -H "Content-Type: application/json" \
  -d '{
    "description": "Payment to Bob",
    "sourceWalletId": {aliceWalletId},
    "destinationWalletId": {bobWalletId},
    "amount": 250.00,
    "type": "TRANSFER"
  }'

# 5. Verify balances
curl -s http://localhost:8080/wallets/{aliceWalletId}/balance   # → 750.00
curl -s http://localhost:8080/wallets/{bobWalletId}/balance     # → 250.00
```

---

## Database Schema

The application creates **5 tables** on both shards via Flyway migrations:

### `users`
| Column | Type        | Constraints |
|--------|-------------|-------------|
| id     | BIGSERIAL   | PRIMARY KEY |
| name   | VARCHAR(255)|             |
| email  | VARCHAR(255)|             |

### `wallet`
| Column     | Type          | Constraints                          |
|------------|---------------|--------------------------------------|
| id         | BIGSERIAL     | PRIMARY KEY                          |
| user_id    | BIGINT        | NOT NULL                             |
| is_active  | BOOLEAN       | NOT NULL                             |
| currency   | VARCHAR(3)    | NOT NULL, DEFAULT 'USD'              |
| balance    | DECIMAL(19,2) | NOT NULL, DEFAULT 0.00               |
| version    | BIGINT        | Optimistic locking                   |
| created_at | TIMESTAMP     | NOT NULL, DEFAULT CURRENT_TIMESTAMP  |
| updated_at | TIMESTAMP     | NOT NULL, DEFAULT CURRENT_TIMESTAMP  |

### `transactions`
| Column                | Type          | Constraints                          |
|-----------------------|---------------|--------------------------------------|
| id                    | BIGSERIAL     | PRIMARY KEY                          |
| description           | VARCHAR(255)  | NOT NULL                             |
| source_wallet_id      | BIGINT        | NOT NULL                             |
| destination_wallet_id | BIGINT        | NOT NULL                             |
| amount                | DECIMAL(19,2) | NOT NULL                             |
| status                | VARCHAR(50)   | NOT NULL (PENDING/SUCCESS/FAILED/CANCELLED) |
| type                  | VARCHAR(50)   | NOT NULL (TRANSFER/DEPOSIT/WITHDRAWAL)      |
| saga_instance_id      | BIGINT        | NOT NULL                             |
| created_date          | TIMESTAMP     | NOT NULL                             |
| updated_date          | TIMESTAMP     | NOT NULL                             |

### `saga_instance`
| Column           | Type          | Constraints                          |
|------------------|---------------|--------------------------------------|
| id               | BIGSERIAL     | PRIMARY KEY                          |
| saga_type        | VARCHAR(100)  | NOT NULL                             |
| status           | VARCHAR(50)   | NOT NULL                             |
| context          | JSONB         | NOT NULL — saga execution data       |
| current_step     | VARCHAR(255)  | NOT NULL                             |
| error_details    | TEXT          |                                      |
| retry_count      | INTEGER       | NOT NULL, DEFAULT 0                  |
| max_retries      | INTEGER       | NOT NULL, DEFAULT 3                  |
| timeout_minutes  | INTEGER       | NOT NULL, DEFAULT 60                 |
| version          | BIGINT        | Optimistic locking                   |
| created_date     | TIMESTAMP     | NOT NULL                             |
| updated_date     | TIMESTAMP     | NOT NULL                             |

### `saga_step`
| Column              | Type          | Constraints                          |
|---------------------|---------------|--------------------------------------|
| id                  | BIGSERIAL     | PRIMARY KEY                          |
| saga_instance_id    | BIGINT        | NOT NULL, FK → saga_instance(id)     |
| step_order          | INTEGER       | NOT NULL (unique per saga)           |
| step_name           | VARCHAR(100)  | NOT NULL                             |
| status              | VARCHAR(50)   | NOT NULL                             |
| error_message       | TEXT          |                                      |
| retry_count         | INTEGER       | NOT NULL, DEFAULT 0                  |
| max_retries         | INTEGER       | NOT NULL, DEFAULT 3                  |
| step_data           | JSONB         |                                      |
| version             | BIGINT        | Optimistic locking                   |

---

## How the Saga Pattern Works

### The Problem
In a sharded database, User A's wallet may live on **Shard 1** and User B's wallet on **Shard 2**. A traditional database transaction cannot span both shards — if the debit succeeds on Shard 1 but the credit fails on Shard 2, you need a way to undo the debit.

### The Solution
The **Saga Orchestration Pattern** breaks the transfer into a sequence of independent local transactions:

1. **Debit Source Wallet** — Local ACID transaction on source wallet's shard
2. **Credit Destination Wallet** — Local ACID transaction on destination wallet's shard
3. **Update Transaction Status** — Mark the transaction as SUCCESS

If **Step 2 fails**, the orchestrator automatically:
1. Detects the failure
2. Finds all completed steps from the `saga_step` table
3. Executes **compensating transactions** in reverse order (credit the debited amount back)
4. Marks the saga as `COMPENSATED`

### Key Components

| Component                   | Responsibility                                              |
|-----------------------------|-------------------------------------------------------------|
| `SagaOrchestrator`          | Interface defining the saga lifecycle                       |
| `SagaOrchestratorImpl`      | Execution engine with retry, compensation, state persistence|
| `TransferSagaService`       | Transfer-specific saga coordinator                          |
| `SagaContext`               | Shared data between steps, serialized as JSONB in DB        |
| `SagaStepInterface`         | Contract for each step: `execute()` + `compensate()`        |
| `SagaStepFactory`           | Step registry mapping step types to implementations         |
| `SagaInstance` / `SagaStep` | JPA entities tracking saga and step state in the database   |

---

## Resilience & Retry Mechanism

The system handles transient failures automatically:

| Exception Type                              | Cause                        |
|---------------------------------------------|------------------------------|
| `ObjectOptimisticLockingFailureException`   | Concurrent entity modification|
| `CannotAcquireLockException`                | Database lock contention      |
| `TransientDataAccessException`              | Temporary database issues     |

**Retry Policy:**
- **Max attempts:** 3 per step (configurable)
- **Backoff:** Exponential — 1s → 2s → 4s → ... (max 10s)
- **Recovery:** After exhausting retries, error is captured and step is marked `FAILED`

---

## Configuration Reference

### application.properties

| Property                                     | Value     | Description                           |
|----------------------------------------------|-----------|---------------------------------------|
| `spring.jpa.hibernate.ddl-auto`              | `none`    | Schema managed by Flyway              |
| `spring.jpa.show-sql`                        | `true`    | SQL logging (disable in production)   |
| `spring.flyway.enabled`                      | `false`   | Flyway auto-config disabled (manual)  |

### Environment Variables (.env)

| Variable           | Default Value                                  | Description          |
|--------------------|------------------------------------------------|----------------------|
| `POSTGRES_USER`    | `postgres`                                     | PostgreSQL username  |
| `POSTGRES_PASS`    | `admin`                                        | PostgreSQL password  |
| `POSTGRES_DB1_URL` | `jdbc:postgresql://localhost:5432/wallet1`     | Shard 1 JDBC URL     |
| `POSTGRES_DB2_URL` | `jdbc:postgresql://localhost:5432/wallet2`     | Shard 2 JDBC URL     |

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| `database "shardwallet1" does not exist` | Create databases: `CREATE DATABASE shardwallet1; CREATE DATABASE shardwallet2;` |
| `password authentication failed` | Check `.env` — ensure `POSTGRES_PASS` matches your PostgreSQL password |
| `Connection refused` on startup | Ensure PostgreSQL service is running |
| Port 8080 already in use | Add `server.port=8081` to `application.properties` |
| Flyway migration errors | Drop `flyway_schema_history` table on both shards and restart |
| `Could not find tools.jar` | Set `JAVA_HOME` to JDK 21 |

---

## Upcoming Features

### Phase 1 — Security & Reliability
- **JWT Authentication & Authorization** — Spring Security with role-based access (USER, ADMIN)
- **Idempotency Keys** — Prevent duplicate transactions on network retries
- **Comprehensive Test Suite** — Unit tests, integration tests with Testcontainers

### Phase 2 — Core Wallet Features
- **Deposit & Withdrawal Sagas** — Proper saga flows for deposits and withdrawals (not just transfers)
- **Multi-Currency Support** — Multiple currency wallets with exchange rate integration
- **Wallet Limits & Controls** — Daily/monthly transaction limits, max balance caps

### Phase 3 — Advanced Transactions
- **Scheduled Transactions** — Recurring payments and future-dated transfers
- **Batch Transfers** — Bulk payment processing
- **Transaction Fees** — Configurable fee structures
- **Transaction Disputes & Reversals** — Dispute workflow with admin resolution

### Phase 4 — Observability & Operations
- **Spring Boot Actuator + Prometheus Metrics** — Health checks, custom saga metrics
- **Saga Monitoring Dashboard** — Real-time saga execution visibility
- **Saga Cleanup Scheduler** — Automatic cleanup of completed/expired sagas
- **Audit Trail** — Immutable log of all balance-affecting operations
- **Notification Service** — Email/SMS alerts for transactions

### Phase 5 — Scale & Performance
- **Async Saga Execution** — Message queue (Kafka/RabbitMQ) for non-blocking saga steps
- **Redis Caching** — Cache frequently accessed wallet data
- **Database Read Replicas** — Read scaling with replica routing

### Phase 6 — Platform Features
- **API Documentation** — Swagger/OpenAPI via springdoc-openapi
- **Webhook System** — Real-time event notifications to external systems
- **Statement Export** — PDF/CSV transaction history export
- **Multi-Tenant Support** — Isolated data per tenant

---

## License

This project is for educational and portfolio purposes.
