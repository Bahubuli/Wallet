# Repository Pattern & Spring Data JPA

## Quick Summary

**Repository Pattern** = An abstraction layer between the domain/service layer and data access logic, providing a collection-like interface for accessing domain entities.
**Spring Data JPA** = Framework that auto-implements repository interfaces — you define method signatures, Spring generates the SQL.

---

## 1. The Repository Abstraction

```
┌──────────────────────────────────────────────┐
│              Service Layer                    │
│   walletRepository.findByUserId(42)          │
│   (Doesn't know or care about SQL/JDBC)      │
└─────────────────┬────────────────────────────┘
                  │  Calls interface method
                  ▼
┌──────────────────────────────────────────────┐
│       Repository Interface                    │
│   WalletRepository extends JpaRepository     │
│   (You define this — no implementation!)      │
└─────────────────┬────────────────────────────┘
                  │  Spring generates implementation at runtime
                  ▼
┌──────────────────────────────────────────────┐
│   SimpleJpaRepository (Spring's impl)        │
│   EntityManager → JPQL → SQL → JDBC          │
└─────────────────┬────────────────────────────┘
                  │
                  ▼
┌──────────────────────────────────────────────┐
│          Database (PostgreSQL)                │
└──────────────────────────────────────────────┘
```

---

## 2. Repository Hierarchy

```
Repository<T, ID>                    ← Marker interface
  └── CrudRepository<T, ID>         ← save, findById, delete, count, existsById
       └── ListCrudRepository       ← findAll() returns List (not Iterable)
            └── JpaRepository<T, ID> ← flush, saveAndFlush, deleteInBatch, Pageable support
                 └── Our Repositories extend this
```

### JpaRepository Methods (Inherited)

| Method | Description |
|--------|------------|
| `save(T entity)` | Insert or update |
| `saveAll(Iterable<T>)` | Batch save |
| `findById(ID id)` | Find by primary key → `Optional<T>` |
| `existsById(ID id)` | Check existence |
| `findAll()` | Get all records |
| `findAll(Pageable)` | Get paginated records |
| `findAll(Sort)` | Get sorted records |
| `count()` | Count all records |
| `deleteById(ID id)` | Delete by primary key |
| `delete(T entity)` | Delete specific entity |
| `flush()` | Force pending changes to DB |
| `saveAndFlush(T entity)` | Save + immediate flush |

---

## 3. Our Repository Interfaces

### WalletRepository

```java
@Repository
public interface WalletRepository extends JpaRepository<Wallet, Long> {
    
    // ── Derived Query Methods (Spring generates SQL from method name) ──
    List<Wallet> findByUserId(Long userId);
    Optional<Wallet> findByIdAndUserId(Long id, Long userId);
    boolean existsByUserId(Long userId);
    
    // ── Paginated Queries ──
    Page<Wallet> findByUserId(Long userId, Pageable pageable);
    Page<Wallet> findByIsActiveTrue(Pageable pageable);
}
```

### TransactionRepository

```java
@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {
    
    // ── Derived Queries ──
    List<Transaction> findBySourceWalletId(Long sourceWalletId);
    List<Transaction> findByDestinationWalletId(Long destinationWalletId);
    Optional<Transaction> findByIdAndSourceWalletId(Long id, Long sourceWalletId);
    
    // ── Paginated Queries ──
    Page<Transaction> findBySourceWalletId(Long sourceWalletId, Pageable pageable);
    Page<Transaction> findByDestinationWalletId(Long destWalletId, Pageable pageable);
    Page<Transaction> findBySourceWalletIdOrDestinationWalletId(
        Long sourceId, Long destId, Pageable pageable);
    
    // ── Filtered Queries ──
    Page<Transaction> findByStatus(TransactionStatus status, Pageable pageable);
    Page<Transaction> findByType(TransactionType type, Pageable pageable);
    Page<Transaction> findBySourceWalletIdAndStatus(
        Long sourceWalletId, TransactionStatus status, Pageable pageable);
    
    // ── Custom JPQL ──
    @Query("SELECT t FROM Transaction t WHERE t.sourceWalletId = :walletId " +
           "OR t.destinationWalletId = :walletId")
    Page<Transaction> findAllByWalletId(@Param("walletId") Long walletId, Pageable pageable);
}
```

### SagaInstanceRepository & SagaStepRepository

```java
@Repository
public interface SagaInstanceRepository extends JpaRepository<SagaInstance, Long> {
    List<SagaInstance> findByStatus(SagaStatus status);
    Optional<SagaInstance> findByIdAndStatus(Long id, SagaStatus status);
    
    // Expired sagas — for recovery/cleanup
    @Query("SELECT s FROM SagaInstance s WHERE s.status = :status " +
           "AND s.updatedAt < :before")
    List<SagaInstance> findExpiredSagas(
        @Param("status") SagaStatus status, @Param("before") Instant before);
    
    // Retryable sagas
    @Query("SELECT s FROM SagaInstance s WHERE s.status IN :statuses " +
           "AND s.retryCount < s.maxRetries")
    List<SagaInstance> findRetryableSagas(@Param("statuses") List<SagaStatus> statuses);
}

@Repository
public interface SagaStepRepository extends JpaRepository<SagaStep, Long> {
    List<SagaStep> findBySagaInstanceIdOrderByStepOrder(Long sagaInstanceId);
    Optional<SagaStep> findBySagaInstanceIdAndStepOrder(Long sagaInstanceId, Integer stepOrder);
}
```

---

## 4. Derived Query Methods

Spring Data JPA parses the method name and generates JPQL/SQL automatically:

```
findByUserId(Long userId)
  ↓ Spring parses
find → SELECT  |  By → WHERE  |  UserId → user_id = ?
  ↓ Generated SQL
SELECT * FROM wallets WHERE user_id = ?
```

### Naming Convention

```
find|read|get|query|search|stream  → SELECT
count                              → SELECT COUNT
exists                             → SELECT EXISTS
delete|remove                      → DELETE

By           → WHERE
And          → AND
Or           → OR
Between      → BETWEEN ? AND ?
LessThan     → < ?
GreaterThan  → > ?
Like         → LIKE ?
IsNull       → IS NULL
IsNotNull    → IS NOT NULL
In           → IN (?, ?, ?)
OrderBy      → ORDER BY
True/False   → = true / = false
```

### Examples

| Method Signature | Generated SQL (simplified) |
|-----------------|---------------------------|
| `findByUserId(Long)` | `WHERE user_id = ?` |
| `findByIdAndUserId(Long, Long)` | `WHERE id = ? AND user_id = ?` |
| `findByIsActiveTrue()` | `WHERE is_active = true` |
| `findByStatusIn(List<Status>)` | `WHERE status IN (?, ?, ?)` |
| `findByCreatedAtBetween(Instant, Instant)` | `WHERE created_at BETWEEN ? AND ?` |
| `findBySagaInstanceIdOrderByStepOrder(Long)` | `WHERE saga_instance_id = ? ORDER BY step_order` |
| `countByStatus(Status)` | `SELECT COUNT(*) WHERE status = ?` |
| `existsByUserId(Long)` | `SELECT EXISTS(... WHERE user_id = ?)` |

---

## 5. Custom Queries with @Query

When method names get too complex, use `@Query`:

```java
// ── JPQL (entity-based, portable) ──
@Query("SELECT t FROM Transaction t WHERE t.sourceWalletId = :walletId " +
       "OR t.destinationWalletId = :walletId")
Page<Transaction> findAllByWalletId(@Param("walletId") Long walletId, Pageable pageable);

// ── Native SQL (database-specific, raw performance) ──
@Query(value = "SELECT * FROM transactions WHERE source_wallet_id = :wid " +
               "AND amount > :minAmt", nativeQuery = true)
List<Transaction> findLargeTransactions(@Param("wid") Long walletId, 
                                         @Param("minAmt") BigDecimal minAmount);

// ── Modifying Queries ──
@Modifying
@Query("UPDATE SagaInstance s SET s.status = :status WHERE s.id = :id")
int updateStatus(@Param("id") Long id, @Param("status") SagaStatus status);
```

### JPQL vs Native SQL

| Feature | JPQL | Native SQL |
|---------|------|-----------|
| Syntax | Entity/field names | Table/column names |
| Portability | Database-agnostic | Database-specific |
| Entity mapping | Automatic | Manual or `@SqlResultSetMapping` |
| Performance | Hibernate generates SQL | Direct execution |
| Advanced features | Limited (no window functions) | Full SQL power |

---

## 6. Pagination Support

```java
// Repository — just add Pageable parameter
Page<Transaction> findBySourceWalletId(Long id, Pageable pageable);

// Service — creates pageable from controller params
public Page<TransactionResponseDTO> getTransactions(Long walletId, Pageable pageable) {
    return transactionRepository.findBySourceWalletId(walletId, pageable)
        .map(this::mapToResponseDTO);  // Page.map transforms content
}

// Controller — Spring auto-resolves Pageable from query params
@GetMapping("/wallet/{walletId}")
public ResponseEntity<Page<TransactionResponseDTO>> getTransactions(
    @PathVariable Long walletId,
    @PageableDefault(size = 20, sort = "createdAt", 
                     direction = Sort.Direction.DESC) Pageable pageable) {
    return ResponseEntity.ok(transactionService.getTransactions(walletId, pageable));
}
```

Client calls: `GET /transactions/wallet/42?page=0&size=20&sort=createdAt,desc`

---

## 7. Return Types

```java
// Single result
Optional<Wallet> findById(Long id);           // ✅ Safe — no NPE
Wallet findByEmail(String email);             // ⚠️ Returns null if not found

// Multiple results
List<Transaction> findByStatus(Status s);     // All results in memory
Page<Transaction> findByStatus(Status s, Pageable p);  // Paginated + total count
Slice<Transaction> findByStatus(Status s, Pageable p); // Paginated, no total count
Stream<Transaction> findByStatus(Status s);   // Lazy loading, must close stream

// Existence / Count
boolean existsByUserId(Long userId);
long countByStatus(Status status);
```

### Page vs Slice

| Feature | `Page<T>` | `Slice<T>` |
|---------|-----------|------------|
| Total count | ✅ `getTotalElements()` | ❌ Not available |
| Total pages | ✅ `getTotalPages()` | ❌ |
| Has next | ✅ `hasNext()` | ✅ `hasNext()` |
| Performance | ❌ Extra COUNT query | ✅ No COUNT query |
| Use case | UI with page numbers | Infinite scroll / "Load more" |

---

## 8. Anti-Patterns

```
❌ Fetching all records then filtering in Java
   List<Wallet> all = walletRepository.findAll();
   all.stream().filter(w -> w.getUserId() == 42)...
   → Use findByUserId(42) — let the database filter

❌ N+1 Query Problem
   wallets.forEach(w -> w.getTransactions().size()); // N extra queries
   → Use @EntityGraph or JOIN FETCH in @Query

❌ Ignoring Optional
   Wallet w = walletRepository.findById(id).get(); // ← NPE risk!
   → Use .orElseThrow(() -> new ResourceNotFoundException(...))

❌ Using native queries for simple operations
   @Query(value = "SELECT * FROM wallets WHERE user_id = ?", nativeQuery = true)
   → Just use findByUserId(Long userId) — derived query is cleaner

❌ Not using pagination for potentially large result sets
   List<Transaction> findAll();  // Millions of records in memory!
   → Always use Page<Transaction> findAll(Pageable pageable)
```

---

## 9. Interview Quick-Fire

**Q: What's the difference between CrudRepository and JpaRepository?**
A: `JpaRepository` extends `CrudRepository` and adds JPA-specific features: `flush()`, `saveAndFlush()`, batch deletes (`deleteInBatch`), `findAll(Pageable)`, and `findAll(Sort)`.

**Q: How does Spring Data JPA generate implementations?**
A: At application startup, Spring scans for interfaces extending `Repository`, creates proxy implementations using `SimpleJpaRepository` as base, and registers them as Spring beans. It parses method names into JPQL queries or uses `@Query` annotations.

**Q: What is the N+1 problem and how do you solve it?**
A: When loading a parent entity and its lazy-loaded children, Hibernate executes 1 query for the parent + N queries for each child. Solutions: `@EntityGraph`, `JOIN FETCH` in JPQL, `@BatchSize`, or `@Fetch(FetchMode.SUBSELECT)`.

**Q: Page vs Slice — when to use which?**
A: Use `Page` when you need total count (e.g., "Page 3 of 10" UI). Use `Slice` for infinite scroll / "Load more" patterns — it skips the expensive `COUNT(*)` query.

**Q: Can derived query methods handle complex queries?**
A: They handle basic WHERE, AND, OR, ORDER BY, comparison operators, and pagination. For complex queries (subqueries, joins, window functions, aggregations), use `@Query` with JPQL or native SQL.

---

## Key Takeaway

> Our repositories extend **`JpaRepository`** for full CRUD + pagination support. We use **derived query methods** (`findByUserId`, `findByStatus`) for simple queries and **`@Query` with JPQL** for complex ones (OR conditions, expiration checks, retryable saga lookups). **`Pageable`** is used throughout for paginated responses, and `Optional<T>` return types prevent null pointer exceptions.
