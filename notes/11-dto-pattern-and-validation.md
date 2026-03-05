# DTO Pattern, Bean Validation & Input Sanitization

## Quick Summary

**DTO (Data Transfer Object)** = A plain object used to transfer data between layers, decoupling the API contract from the internal entity model.
**Bean Validation** = Declarative validation using annotations (`@NotNull`, `@Email`, `@Size`) applied on DTOs to validate input at the controller layer.

---

## 1. Why DTOs?

### Without DTOs (Entity Exposed Directly)

```java
// ❌ BAD: Controller returns JPA entity directly
@PostMapping("/create")
public ResponseEntity<Wallet> createWallet(@RequestBody Wallet wallet) {
    return ResponseEntity.ok(walletRepository.save(wallet));
}
```

**Problems:**
| Problem | Example |
|---------|---------|
| **Over-exposure** | Client sees `version`, `createdAt`, internal IDs |
| **Over-posting (mass assignment)** | Client sends `{"version": 999}` → bypasses optimistic locking |
| **Tight coupling** | Changing entity field name breaks all API clients |
| **Circular references** | `SagaInstance.steps` → `SagaStep.sagaInstance` → infinite JSON |
| **Validation mixed with persistence** | JPA annotations + validation annotations on same class |

### With DTOs (Decoupled)

```
Client ←→ DTO ←→ Service ←→ Entity ←→ Database

Request:  Client sends WalletRequestDTO → Service maps to Wallet entity
Response: Service maps Wallet entity → WalletResponseDTO → Client receives

Benefits:
✅ Client only sees what you choose to expose
✅ Entity can change without breaking API
✅ Different DTOs for create, update, response
✅ Validation only on request DTOs
✅ No circular reference issues
```

---

## 2. Our DTO Structure

### Request DTOs (Input — with validation)

```java
@Data @NoArgsConstructor @AllArgsConstructor
public class WalletRequestDTO {
    @NotNull(message = "User ID must not be null")
    @Positive(message = "User ID must be a positive number")
    private Long userId;

    private Boolean isActive = true;      // Optional with default

    @DecimalMin(value = "0.00", message = "Balance cannot be negative")
    private BigDecimal balance = BigDecimal.ZERO;  // Optional with default
}
```

### Response DTOs (Output — no validation)

```java
@Data @NoArgsConstructor @AllArgsConstructor
public class WalletResponseDTO {
    private Long id;
    private Long userId;
    private Boolean isActive;
    private BigDecimal balance;
    // No version, no createdAt, no updatedAt — internal fields hidden
}
```

### Error Response DTO

```java
@Data @Builder @NoArgsConstructor @AllArgsConstructor
public class ErrorResponseDTO {
    private int status;       // HTTP status code
    private String error;     // Error category
    private String message;   // Human-readable details
    private Instant timestamp; // When error occurred
}
```

---

## 3. Bean Validation Annotations

### Common Annotations Used

| Annotation | Target | Example |
|-----------|--------|---------|
| `@NotNull` | Any field | `@NotNull private Long userId` |
| `@NotBlank` | String (not null, not empty, not whitespace) | `@NotBlank private String name` |
| `@Size` | String/Collection length | `@Size(min=2, max=100) private String name` |
| `@Email` | Email format | `@Email private String email` |
| `@Positive` | Positive number | `@Positive private Long id` |
| `@DecimalMin` | Minimum decimal | `@DecimalMin("0.01") private BigDecimal amount` |
| `@Min` / `@Max` | Number range | `@Min(1) @Max(100) private int page` |
| `@Pattern` | Regex match | `@Pattern(regexp="^[A-Z]{3}$") private String currency` |
| `@NotEmpty` | Collection not empty | `@NotEmpty private List<Item> items` |
| `@Past` / `@Future` | Date constraints | `@Past private LocalDate dob` |

### Validation in Our DTOs

```java
// UserRequestDTO
@NotBlank(message = "Name must not be blank")
@Size(min = 2, max = 100, message = "Name must be between 2 and 100 characters")
private String name;

@NotBlank(message = "Email must not be blank")
@Email(message = "Email must be a valid email address")
private String email;

// TransactionRequestDTO
@NotNull(message = "Source wallet ID must not be null")
@Positive(message = "Source wallet ID must be a positive number")
private Long sourceWalletId;

@NotNull(message = "Amount must not be null")
@DecimalMin(value = "0.01", message = "Amount must be greater than 0")
private BigDecimal amount;

@NotNull(message = "Transaction type must not be null")
private TransactionType type;  // Enum — automatically validated
```

### Activating Validation in Controller

```java
@PostMapping("/create")
public ResponseEntity<TransactionResponseDTO> createTransaction(
        @Valid @RequestBody TransactionRequestDTO req) {
    //  ↑ @Valid triggers Bean Validation before method executes
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(transactionService.createTransaction(req));
}
```

`@Valid` triggers validation. If validation fails, Spring throws `MethodArgumentNotValidException` → caught by `GlobalExceptionHandler`.

---

## 4. How Validation Errors Are Handled

```java
// GlobalExceptionHandler.java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ResponseEntity<ErrorResponseDTO> handleValidationException(
        MethodArgumentNotValidException ex) {
    
    String message = ex.getBindingResult().getFieldErrors().stream()
        .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
        .collect(Collectors.joining("; "));
    
    return buildResponse(HttpStatus.BAD_REQUEST, "Validation Failed", message);
}
```

### Example: Invalid Request

```json
// Request
POST /transactions/create
{
  "sourceWalletId": -5,
  "amount": 0,
  "type": null
}

// Response (400 Bad Request)
{
  "status": 400,
  "error": "Validation Failed",
  "message": "sourceWalletId: Source wallet ID must be a positive number; amount: Amount must be greater than 0; type: Transaction type must not be null; destinationWalletId: Destination wallet ID must not be null",
  "timestamp": "2024-03-04T10:30:00Z"
}
```

---

## 5. DTO Mapping Patterns

### Manual Mapping (Our Approach)

```java
// In WalletService.java
private WalletResponseDTO mapToResponseDTO(Wallet w) {
    return new WalletResponseDTO(
        w.getId(), w.getUserId(), w.getIsActive(), w.getBalance()
    );
}

// Request → Entity
public WalletResponseDTO createWallet(WalletRequestDTO req) {
    Wallet w = new Wallet();
    w.setUserId(req.getUserId());
    w.setIsActive(req.getIsActive() != null ? req.getIsActive() : true);
    w.setBalance(req.getBalance() != null ? req.getBalance() : BigDecimal.ZERO);
    Wallet saved = walletRepository.save(w);
    return mapToResponseDTO(saved);
}
```

### Alternative: MapStruct (Auto-mapping)

```java
@Mapper(componentModel = "spring")
public interface WalletMapper {
    WalletResponseDTO toResponse(Wallet entity);
    Wallet toEntity(WalletRequestDTO dto);
}
```

### Alternative: ModelMapper

```java
ModelMapper mapper = new ModelMapper();
WalletResponseDTO dto = mapper.map(wallet, WalletResponseDTO.class);
```

### Comparison

| Approach | Pros | Cons |
|----------|------|------|
| **Manual** ✅ | Full control, no magic, easy to debug | Boilerplate |
| **MapStruct** | Compile-time, zero runtime cost, type-safe | Learning curve, generated code |
| **ModelMapper** | Convention-based, minimal code | Runtime reflection, harder to debug |

---

## 6. Validation vs Business Rules

```
VALIDATION (DTO layer — syntactic):
  "Is the amount a positive number?"
  "Is the email format valid?"
  "Is the wallet ID present?"
  → Handled by @Valid + annotations
  → Returns 400 Bad Request

BUSINESS RULES (Service layer — semantic):
  "Does the source wallet have sufficient balance?"
  "Is the wallet active?"
  "Does the user exist?"
  → Handled by service code + custom exceptions
  → Returns 400 (BusinessException) or 404 (ResourceNotFoundException)
```

```java
// DTO Validation (syntactic)
@DecimalMin(value = "0.01") private BigDecimal amount;  // "Is it positive?"

// Business Validation (semantic) — in WalletService
if (!src.hasSufficientBalance(req.getAmount()))
    throw new BusinessException("Insufficient balance");  // "Is it enough?"
```

---

## 7. Interview Quick-Fire

**Q: Why use DTOs instead of entities in API responses?**
A: DTOs decouple the API contract from the internal data model. Entities may contain sensitive fields (`version`, internal IDs), circular references (JPA bidirectional mappings), and may change structure without affecting the API.

**Q: Where should validation happen?**
A: Two layers: (1) **Syntactic validation** on DTOs with Bean Validation annotations — checks format and presence. (2) **Semantic/business validation** in service layer — checks business rules like sufficient balance, active status, existence.

**Q: What's the difference between @NotNull, @NotEmpty, and @NotBlank?**
A: `@NotNull`: value is not null (but can be empty string). `@NotEmpty`: not null AND not empty (works on strings, collections). `@NotBlank`: not null, not empty, AND not just whitespace (strings only).

**Q: What happens if @Valid is missing on the controller parameter?**
A: Validation annotations are ignored. The raw (potentially invalid) data passes straight to the service layer, which may cause unexpected errors deeper in the code.

---

## Key Takeaway

> We use **separate Request/Response DTOs** for every entity to decouple the API from the data model. **Bean Validation annotations** (`@NotNull`, `@Positive`, `@DecimalMin`, `@Email`) on request DTOs provide syntactic validation at the controller layer, while **business rule validation** happens in the service layer. Validation errors are caught by `GlobalExceptionHandler` and returned as structured `ErrorResponseDTO` objects.
