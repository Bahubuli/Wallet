# REST API Design & Pagination

## Quick Summary

**REST (Representational State Transfer)** = An architectural style for building web APIs around resources, using HTTP methods (GET, POST, PUT, DELETE) with stateless communication and standard status codes.
**Pagination** = Breaking large result sets into pages to prevent memory/performance issues, using either offset-based (`page=0&size=20`) or cursor-based approaches.

---

## 1. REST Principles Applied in Our Project

| Principle | How We Apply It |
|-----------|----------------|
| **Resource-oriented URLs** | `/users`, `/wallets`, `/transactions` |
| **HTTP methods as verbs** | GET = read, POST = create, PUT = update, DELETE = delete |
| **Stateless** | No server-side sessions; each request is self-contained |
| **Standard status codes** | 200, 201, 400, 404, 500 |
| **JSON responses** | All endpoints return JSON (DTOs) |
| **Layered architecture** | Controller → Service → Repository |

---

## 2. Our REST API Design

### URL Structure

```
/users                          → User collection
/users/{id}                     → Single user
/users/{id}/wallets             → Wallets belonging to a user

/wallets                        → Wallet collection
/wallets/{id}                   → Single wallet
/wallets/user/{userId}          → Wallets by user (alternative)

/transactions                   → Transaction collection
/transactions/{id}              → Single transaction
/transactions/wallet/{walletId} → Transactions for a wallet
/transactions/create            → Create transaction (POST)
```

### HTTP Method Mapping

```java
@RestController
@RequestMapping("/wallets")
public class WalletController {

    @PostMapping("/create")                 // POST   → Create new resource
    public ResponseEntity<WalletResponseDTO> createWallet(
        @Valid @RequestBody WalletRequestDTO req) {
        return ResponseEntity.status(HttpStatus.CREATED)  // 201
            .body(walletService.createWallet(req));
    }

    @GetMapping("/{id}")                    // GET    → Read single resource
    public ResponseEntity<WalletResponseDTO> getWallet(@PathVariable Long id) {
        return ResponseEntity.ok(walletService.getWalletById(id));  // 200
    }

    @GetMapping("/user/{userId}")           // GET    → Read collection (paginated)
    public ResponseEntity<Page<WalletResponseDTO>> getWalletsByUser(
        @PathVariable Long userId,
        @PageableDefault(size = 10, sort = "id") Pageable pageable) {
        return ResponseEntity.ok(walletService.getWalletsByUser(userId, pageable));
    }

    @PutMapping("/{id}")                    // PUT    → Update existing resource
    public ResponseEntity<WalletResponseDTO> updateWallet(
        @PathVariable Long id,
        @Valid @RequestBody WalletRequestDTO req) {
        return ResponseEntity.ok(walletService.updateWallet(id, req));  // 200
    }
}
```

### HTTP Status Codes We Use

| Code | Meaning | When |
|------|---------|------|
| **200 OK** | Success | GET, PUT responses |
| **201 Created** | Resource created | POST responses |
| **400 Bad Request** | Invalid input / business error | Validation failure, business rule violation |
| **404 Not Found** | Resource doesn't exist | `findById` returns empty |
| **500 Internal Server Error** | Unexpected failure | Unhandled exceptions |

---

## 3. Pagination Deep Dive

### Why Paginate?

```
WITHOUT pagination:
  GET /transactions → Returns 10 million records
  → Server: OutOfMemoryError (loading all into heap)
  → Network: 500MB response body
  → Client: Browser/app crashes

WITH pagination:
  GET /transactions?page=0&size=20 → Returns 20 records
  → Server: Efficient SQL (LIMIT 20 OFFSET 0)
  → Network: ~2KB response
  → Client: Fast render, smooth UX
```

### Spring's Pageable Mechanism

```
Client Request:
  GET /wallets/user/42?page=0&size=20&sort=createdAt,desc

         ↓ Spring resolves Pageable from query params

Controller:
  @GetMapping("/user/{userId}")
  public ResponseEntity<Page<WalletResponseDTO>> getWallets(
      @PathVariable Long userId,
      @PageableDefault(size = 10, sort = "id") Pageable pageable) {
                        ↑ defaults if client omits params

         ↓ passes Pageable to service/repository

Repository:
  Page<Wallet> findByUserId(Long userId, Pageable pageable);

         ↓ Spring Data generates SQL

SQL:
  SELECT * FROM wallets WHERE user_id = 42
  ORDER BY created_at DESC
  LIMIT 20 OFFSET 0;

  SELECT COUNT(*) FROM wallets WHERE user_id = 42;  -- for total count
```

### @PageableDefault Annotation

```java
@PageableDefault(
    size = 20,                        // Default page size
    page = 0,                         // Default page number (0-indexed)
    sort = "createdAt",               // Default sort field
    direction = Sort.Direction.DESC   // Default sort direction
)
Pageable pageable
```

If client sends no query params → uses these defaults.
If client sends `?page=2&size=50&sort=amount,asc` → overrides defaults.

### Page<T> Response Structure

```json
{
  "content": [                    // Actual data for this page
    { "id": 101, "userId": 42, "balance": 500.00 },
    { "id": 102, "userId": 42, "balance": 1200.00 }
  ],
  "pageable": {
    "pageNumber": 0,              // Current page (0-indexed)
    "pageSize": 20,               // Requested page size
    "sort": {
      "sorted": true,
      "orders": [{ "property": "createdAt", "direction": "DESC" }]
    }
  },
  "totalElements": 156,           // Total records across all pages
  "totalPages": 8,                // Total pages (ceil(156/20))
  "size": 20,                     // Page size
  "number": 0,                    // Page number
  "first": true,                  // Is first page?
  "last": false,                  // Is last page?
  "numberOfElements": 20,         // Elements on THIS page
  "empty": false                  // Is content empty?
}
```

---

## 4. Offset vs Cursor Pagination

### Offset-Based (Our Approach)

```sql
-- Page 0: OFFSET 0
SELECT * FROM transactions WHERE wallet_id = 42 ORDER BY created_at DESC LIMIT 20 OFFSET 0;

-- Page 1: OFFSET 20
SELECT * FROM transactions WHERE wallet_id = 42 ORDER BY created_at DESC LIMIT 20 OFFSET 20;

-- Page 500: OFFSET 10000 ← Problem: DB scans & discards 10000 rows!
SELECT * FROM transactions WHERE wallet_id = 42 ORDER BY created_at DESC LIMIT 20 OFFSET 10000;
```

### Cursor-Based (Keyset Pagination)

```sql
-- First page
SELECT * FROM transactions WHERE wallet_id = 42
ORDER BY created_at DESC LIMIT 20;

-- Next page: use last item's created_at as cursor
SELECT * FROM transactions WHERE wallet_id = 42
  AND created_at < '2024-03-01T10:30:00Z'    -- cursor from last item
ORDER BY created_at DESC LIMIT 20;

-- Advantage: Always scans only 20 rows regardless of page depth
```

### Comparison

| Feature | Offset-Based | Cursor-Based |
|---------|-------------|-------------|
| Implementation | Simple (`?page=N&size=M`) | Complex (encode/decode cursor) |
| Jump to page | ✅ `?page=50` directly | ❌ Must traverse sequentially |
| Deep page performance | ❌ O(offset + limit) | ✅ O(limit) always |
| Data consistency | ❌ Duplicates/gaps on inserts | ✅ Stable results |
| Total count | ✅ Easy (COUNT query) | ❌ Hard/expensive |
| Use case | Admin dashboards, reports | Social feeds, infinite scroll |
| Spring support | ✅ Built-in `Pageable` | ⚠️ Manual implementation |

---

## 5. Sorting

```java
// Controller — via @PageableDefault
@PageableDefault(sort = "createdAt", direction = Sort.Direction.DESC)

// Client can override
GET /transactions/wallet/42?sort=amount,desc&sort=createdAt,asc  // Multi-sort

// Programmatic sorting
Sort sort = Sort.by(Sort.Direction.DESC, "createdAt")
    .and(Sort.by(Sort.Direction.ASC, "amount"));
PageRequest pageRequest = PageRequest.of(0, 20, sort);
```

---

## 6. REST Best Practices

### URL Design

```
✅ Nouns, not verbs:   /wallets, /transactions
❌ Verbs in URL:       /getWallet, /createTransaction

✅ Plural resources:   /wallets, /users
❌ Singular:           /wallet, /user

✅ Nested resources:   /users/{id}/wallets
❌ Flat everything:    /walletsByUser?userId=42

✅ Consistent casing:  /wallet-transactions (kebab-case)
❌ Mixed casing:       /walletTransactions, /Wallet_Transactions
```

### Idempotency

| Method | Idempotent? | Safe? | Description |
|--------|------------|-------|-------------|
| **GET** | ✅ | ✅ | Read — no side effects |
| **PUT** | ✅ | ❌ | Replace — same result if repeated |
| **DELETE** | ✅ | ❌ | Delete — already gone if repeated |
| **POST** | ❌ | ❌ | Create — repeating creates duplicates |
| **PATCH** | ❌ | ❌ | Partial update — may not be idempotent |

### Content Negotiation

```java
@RestController  // All methods return JSON by default
@RequestMapping("/wallets")
public class WalletController {
    // @RestController = @Controller + @ResponseBody on every method
    // Spring uses Jackson to serialize objects to JSON
    // Content-Type: application/json (response)
    // Accept: application/json (request)
}
```

---

## 7. ResponseEntity Usage

```java
// ── Created (201) ──
return ResponseEntity.status(HttpStatus.CREATED)
    .body(walletService.createWallet(req));

// ── OK (200) ──
return ResponseEntity.ok(walletService.getWalletById(id));

// ── No Content (204) ──
return ResponseEntity.noContent().build();

// ── With custom headers ──
return ResponseEntity.ok()
    .header("X-Total-Count", String.valueOf(total))
    .body(data);

// ── Not Found (404) — handled by exception, not manually ──
throw new ResourceNotFoundException("Wallet not found: " + id);
// → GlobalExceptionHandler returns 404 with ErrorResponseDTO
```

---

## 8. @RequestMapping Variants

```java
@RequestMapping("/wallets")          // Base path for all methods in controller

@GetMapping("/{id}")                 // GET  /wallets/{id}
@PostMapping("/create")              // POST /wallets/create
@PutMapping("/{id}")                 // PUT  /wallets/{id}
@DeleteMapping("/{id}")              // DELETE /wallets/{id}
@PatchMapping("/{id}")               // PATCH /wallets/{id}

// Path variables
@GetMapping("/{id}")
public ResponseEntity<?> getWallet(@PathVariable Long id) { ... }

// Query parameters
@GetMapping("/search")
public ResponseEntity<?> search(@RequestParam String name,
                                 @RequestParam(required = false) String status) { ... }

// Request body (JSON)
@PostMapping("/create")
public ResponseEntity<?> create(@Valid @RequestBody WalletRequestDTO req) { ... }
```

---

## 9. Interview Quick-Fire

**Q: What makes an API RESTful?**
A: Resource-oriented URLs, correct HTTP method usage, stateless communication, standard status codes, and media type representation (typically JSON). Optional: HATEOAS (hypermedia links in responses).

**Q: Why offset pagination is bad for deep pages?**
A: `OFFSET 10000 LIMIT 20` makes the DB scan and discard 10,000 rows before returning 20. O(offset + limit) complexity. Cursor-based pagination avoids this by using a WHERE condition on the last seen value.

**Q: Difference between @Controller and @RestController?**
A: `@RestController` = `@Controller` + `@ResponseBody`. With `@Controller`, methods return view names (HTML). With `@RestController`, return values are serialized to JSON/XML automatically.

**Q: How does Spring resolve Pageable from query parameters?**
A: Spring's `PageableHandlerMethodArgumentResolver` parses `page`, `size`, and `sort` query parameters automatically. `@PageableDefault` provides fallback values if params are missing.

**Q: PUT vs PATCH?**
A: `PUT` replaces the entire resource (all fields required). `PATCH` partially updates (only changed fields). PUT is idempotent; PATCH may not be.

---

## Key Takeaway

> We follow REST conventions with **resource-based URLs** (`/wallets`, `/transactions`), proper **HTTP methods** (GET/POST/PUT), and correct **status codes** (201 for create, 404 for not found). **Spring's `Pageable`** with `@PageableDefault` provides offset-based pagination with sorting out of the box. `Page<T>` responses include total count, page metadata, and the data slice. For high-scale systems, cursor-based pagination would replace offset-based for deep page performance.
