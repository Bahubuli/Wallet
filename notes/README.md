# Wallet Project — Study Notes Index

> **Purpose**: Quick-revision notes 

---

## Core Distributed Systems Concepts

| # | Topic | Key Concepts |
|---|-------|-------------|
| 01 | [Optimistic vs Pessimistic Locking](01-optimistic-vs-pessimistic-locking.md) | `@Version`, MVCC, retry-on-conflict, when to choose each |
| 02 | [Saga Orchestration Pattern](02-saga-pattern.md) | Orchestrator vs Choreography, step interface, compensation, state machine |
| 03 | [Two-Phase Commit vs Saga](03-two-phase-commit-vs-saga.md) | 2PC problems, CAP positioning, decision matrix, alternatives (TCC, Outbox) |
| 06 | [Compensating Transactions](06-compensating-transactions.md) | LIFO compensation, semantic reversal, idempotency, 4 design rules |
| 08 | [ACID & Distributed Transactions](08-acid-and-distributed-transactions.md) | ACID properties, isolation levels, CAP theorem, BASE vs ACID |

## Data Architecture & Storage

| # | Topic | Key Concepts |
|---|-------|-------------|
| 04 | [Database Sharding](04-database-sharding.md) | ShardingSphere, hash/range/list/directory strategies, shard key selection |
| 05 | [Snowflake ID Generation](05-snowflake-id-generation.md) | 64-bit structure, monotonicity, clock skew, UUID/ULID comparison |
| 09 | [Data Co-location](09-data-co-location.md) | Related entity placement, shard key design, entity group pattern |
| 10 | [Database Migrations (Flyway)](10-database-migrations-flyway.md) | Versioned migrations, Flyway + sharding, programmatic execution |
| 13 | [JSONB & PostgreSQL Features](13-jsonb-postgresql-features.md) | JSONB vs JSON, `@JdbcTypeCode`, `@ColumnTransformer`, GIN indexing |

## Resilience & Reliability

| # | Topic | Key Concepts |
|---|-------|-------------|
| 07 | [Retry & Exponential Backoff](07-retry-and-exponential-backoff.md) | Transient vs permanent failures, `RetryTemplate`, jitter, circuit breaker |

## Spring Boot Patterns

| # | Topic | Key Concepts |
|---|-------|-------------|
| 11 | [DTO Pattern & Bean Validation](11-dto-pattern-and-validation.md) | Request/Response DTOs, `@Valid`, annotation reference, mapping approaches |
| 12 | [Global Exception Handling](12-global-exception-handling.md) | `@RestControllerAdvice`, exception hierarchy, structured error responses |
| 14 | [Repository Pattern & Spring Data JPA](14-repository-pattern-spring-data.md) | Derived queries, `@Query`, `Page` vs `Slice`, N+1 problem |
| 15 | [REST API Design & Pagination](15-rest-api-and-pagination.md) | RESTful conventions, `Pageable`, offset vs cursor pagination |
| 16 | [Transaction Management](16-transaction-management.md) | `@Transactional`, `TransactionTemplate`, propagation, rollback rules, pitfalls |

## Software Design

| # | Topic | Key Concepts |
|---|-------|-------------|
| 17 | [Design Patterns](17-design-patterns.md) | Strategy, Factory, Builder, Template Method, Facade, Rich Domain, DI/IoC |

---

## Tech Stack Quick Reference

| Component | Technology | Version |
|-----------|-----------|---------|
| Framework | Spring Boot | 4.0.1 |
| Language | Java | 21 |
| Build | Gradle | Wrapper |
| Database | PostgreSQL | 2 shards |
| Sharding | Apache ShardingSphere JDBC | 5.5.2 |
| Migrations | Flyway | (Spring Boot managed) |
| Retry | Spring Retry | 2.0.11 |
| Validation | Jakarta Bean Validation | (Spring Boot managed) |

---

## How to Use These Notes

1. **Before interview**: Skim the Quick Summary and Interview Quick-Fire sections in each note
2. **Deep study**: Read full notes with diagrams and code examples
3. **Practice**: Try to explain each concept from memory, then verify against the notes
4. **Code reference**: Every note includes actual code from this project — run it locally to experiment
