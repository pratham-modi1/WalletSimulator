# Aureus: Report 5
## The idempotency day: Add Money and Transfer to Bank (shared ledger posting engine)

**Endpoints built:** `POST /api/wallet/add-money` and `POST /api/wallet/transfer-to-bank`
**Core idea:** two endpoints, **one** transactional money-movement engine (`LedgerPostingService`), protected by authentication, per-user idempotency keys, database row locks and a single DB transaction, with a double-entry record of every movement.
**Plan reference:** this is the "Day 7" work (posting core, Add Money, Transfer to Bank) from the 7-to-11 November schedule.

> Security pipeline (JWT filter, `SecurityContext`, `@AuthenticationPrincipal`) is explained in Report 4, so here it is summarised in one section and not repeated line by line.

---

# Table of contents

1. What was built (files, status, plan vs done)
2. The concepts first: why these endpoints are harder than they look
3. Architecture: who does what
4. The code you wrote (controller, service, posting engine)
5. Runtime trace A: `POST /api/wallet/add-money`
6. Runtime trace B: `POST /api/wallet/transfer-to-bank` (what changes)
7. Idempotency deep dive
8. Concurrency, locking and transactions
9. Error mapping
10. What you tested, and what is still untested
11. Review findings (one real issue, two notes)
12. Interview questions
13. Revision sheet and self-test
14. Next steps

---

# 1. What was built

## 1.1 Files

| File | Layer | Role |
|---|---|---|
| `WalletController` | controller | Two endpoints; reads user id from the JWT principal, amount from the body, key from the header |
| `MoneyRequest` | dto | Request body: `amount` with `@NotNull`, `@DecimalMin("0.01")`, `@Digits(integer=8, fraction=2)` |
| `MoneyResponse` | dto | Response: transaction id, type, amount, status, new bank and wallet balances |
| `MoneyResult` | dto | Wrapper: the `MoneyResponse` plus a `replayed` flag (controller uses it to pick 200 vs 201) |
| `WalletService` | service | Interface the controller depends on |
| `WalletServiceImpl` | service | Maps each endpoint to a transaction type, delegates |
| `LedgerPostingService` | service | **The engine:** validation, locking, idempotency, balances, transaction record, ledger entries |
| `AccountRepository` | repository | `findCustomerAccountsForUpdate(userId, types)` with a pessimistic write lock, ordered by `accountId` |
| `TransactionRepository` | repository | `findByIdempotencyKey` |
| `LedgerEntryRepository` | repository | Saves debit and credit rows |
| `Account` (modified) | entity | `setBalance(BigDecimal)` added |
| Exceptions + `GlobalExceptionHandler` | exception | `InvalidRequestException`, `AccountNotFoundException`, `IdempotencyConflictException`, `InsufficientFundsException`, each mapped to an HTTP status |

## 1.2 Plan vs what you actually did

| Planned | Done? | Note |
|---|---|---|
| Per-user key `U<userId>-<clientKey>` | Yes | Stored key checked against the VARCHAR(100) limit |
| Lock both accounts, ascending id | Yes | One query locks the customer's BANK and WALLET together |
| Idempotency check after the lock | Yes | See review finding 11.1 about one detail of ordering |
| Same key + same operation = replay, different = 409 | Yes | Compares type, sender, receiver, amount (`compareTo`), status |
| Conditional UPDATE for debit/credit | **Different** | You use lock + Java balance check + dirty checking instead. Valid, because the rows are locked |
| Catch unique-key violation and replay | **Not implemented** | Fine as a backstop only; see 11.1 |
| Add Money (BANK to WALLET) | Yes | Type `ADD_MONEY` |
| Transfer to Bank (WALLET to BANK) | Yes | Type `WITHDRAWAL` |

---

# 2. The concepts first

## 2.1 Why a money endpoint is not "just update two numbers"

A single "add ₹500" must do five things **together or not at all**:

1. Decrease BANK by 500
2. Increase WALLET by 500
3. Insert a `Transaction` row (what happened)
4. Insert a DEBIT `LedgerEntry` (BANK, 500)
5. Insert a CREDIT `LedgerEntry` (WALLET, 500)

Around that, four things must hold:

| Problem | Protection used |
|---|---|
| Who is calling? | JWT, `@AuthenticationPrincipal` (never a `userId` from the body) |
| Retry after a lost response moves money twice | **Idempotency key** |
| Two simultaneous requests overspend the same balance | **Pessimistic row locks** |
| Crash halfway leaves half-done changes | **`@Transactional`** (all-or-nothing) |

## 2.2 Idempotency in one paragraph

An operation is idempotent if repeating the **same logical request** has no additional effect after the first success. The client sends a unique key per logical operation (here, the `Idempotency-Key` header). If a retry arrives with the same key, the server returns the original result instead of moving money again. It does **not** mean the server sees only one HTTP request; it means duplicate requests don't create duplicate money movements.

**The scenario it exists for:**
```text
1. Client sends "add ₹500"
2. Server debits, credits, commits
3. Network drops, client never sees the response
4. Client can't tell whether it worked -> retries
5. Without idempotency: second ₹500 moves.   With it: original result returned.
```

## 2.3 Why the key must come from the client

Two separate deposits of ₹500 are legitimately different operations. The server can't guess intent from "same amount". Only the client knows "this is a retry" versus "this is a new deposit".

| Request | Meaning |
|---|---|
| Same key + same details | A retry: replay the original |
| Same key + different details | Misuse: reject (409) |
| Different key + same details | A new operation: execute |

**Frontend rule for later:** create the key once per logical operation (for example `crypto.randomUUID()`), and reuse it for every retry of *that* operation. A new key per attempt defeats the whole mechanism.

## 2.4 Idempotency is not exactly-once delivery

Requests can arrive many times. Idempotency makes repeated arrival **safe**. It does not guarantee exactly-once execution across distributed systems. In interviews, say it this way.

---

# 3. Architecture: who does what

```text
WalletController            HTTP: header, body, principal -> picks 200/201
      |
WalletService (interface)   contract the controller depends on
      |
WalletServiceImpl           chooses ADD_MONEY or WITHDRAWAL
      |
LedgerPostingService        @Transactional engine (one DB transaction)
      |-- UserRepository          user ACTIVE?
      |-- AccountRepository       lock + load BANK and WALLET
      |-- TransactionRepository   idempotency lookup, save transaction
      |-- LedgerEntryRepository   save DEBIT and CREDIT rows
```

The key design decision: `WalletServiceImpl` contains **no** money logic. Both endpoints call `postMoneyMovement(...)` with a different `TransactionType`, and that parameter decides the direction. The delicate code exists once, so it can't drift between two copies.

| Endpoint | Type | Source | Destination |
|---|---|---|---|
| `add-money` | `ADD_MONEY` | BANK | WALLET |
| `transfer-to-bank` | `WITHDRAWAL` | WALLET | BANK |

(`WITHDRAWAL` is the type your schema reserved for money leaving the wallet.)

---

# 4. The code you wrote

## 4.1 `WalletController` (key parts)

```java
@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) { this.walletService = walletService; }

    @PostMapping("/add-money")
    public ResponseEntity<MoneyResponse> addMoney(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody MoneyRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String clientKey) {

        validateClientKey(clientKey);

        MoneyResult result = walletService.addMoney(
                authenticatedUser.userId(), request.getAmount(), clientKey);

        HttpStatus status = result.isReplayed() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result.getResponse());
    }
    // transferToBank is identical except it calls walletService.transferToBank(...)
}
```

Points worth understanding:

| Detail | Why |
|---|---|
| `@AuthenticationPrincipal` | The identity comes from the verified JWT. The client can never choose whose account is touched |
| `@Valid @RequestBody MoneyRequest` | Jackson builds the DTO, then validation runs before the method body |
| `@RequestHeader(value=..., required=false)` | With `required=false`, a missing header doesn't produce Spring's default error; your own `validateClientKey` throws `InvalidRequestException` so the error shape matches the rest of the API (400) |
| `validateClientKey` | Missing, blank, or longer than 64 characters is rejected at the HTTP boundary |
| `result.isReplayed() ? 200 : 201` | New transaction created = 201 Created; replay of an existing one = 200 OK |
| Body is `result.getResponse()` | The `replayed` flag controls the status only, and isn't exposed in JSON |

## 4.2 `WalletServiceImpl`

```java
@Override
public MoneyResult addMoney(Integer userId, BigDecimal amount, String clientKey) {
    return ledgerPostingService.postMoneyMovement(userId, amount, clientKey, TransactionType.ADD_MONEY);
}

@Override
public MoneyResult transferToBank(Integer userId, BigDecimal amount, String clientKey) {
    return ledgerPostingService.postMoneyMovement(userId, amount, clientKey, TransactionType.WITHDRAWAL);
}
```

Deliberately thin. It is **not** `@Transactional`; the transaction is owned by `LedgerPostingService`.

## 4.3 `LedgerPostingService.postMoneyMovement` (the engine)

Annotated `@Transactional`. Full source is in Appendix A. The 12 steps:

| Step | Code (abridged) | Purpose |
|---|---|---|
| 1 | `validateInput(userId, amount, clientKey, type)` | Service-level checks, independent of the controller |
| 2 | `userRepository.findById(userId)`, must be `ACTIVE` | The business operation checks user state itself |
| 3 | `storedKey = "U" + userId + "-" + clientKey` (max 100) | Per-user key scope |
| 4 | `accountRepository.findCustomerAccountsForUpdate(userId, [BANK, WALLET])` | **Locks** both accounts; ordered by id |
| 4b | Loop: exactly one BANK and one WALLET, else exception | Defends against corrupt data |
| 5 | Map source and destination from the transaction type | Direction |
| 6 | `findByIdempotencyKey(storedKey)`; compare; replay or 409 | Idempotency |
| 7 | `source.getBalance().compareTo(amount) < 0` → `InsufficientFundsException` | Funds check (after the lock) |
| 8 | `source.setBalance(old - amount)`, `destination.setBalance(old + amount)` | Balance change via dirty checking |
| 9-10 | `new Transaction(..., COMPLETED, storedKey, null)`, `saveAndFlush` | The record of what happened |
| 11-12 | Two `LedgerEntry` saves: DEBIT on source, CREDIT on destination | Double-entry audit trail |
| End | `buildResult(...)` returns `MoneyResult(response, replayed=false)`, then Spring commits | |

`validateInput` rules: user id present; amount non-null and > 0; `scale() <= 2` and integer digits <= 8; key non-blank and <= 64 characters; transaction type non-null.

---

# 5. Runtime trace A: `POST /api/wallet/add-money`

**Example used throughout:** user id 5, BANK ₹5,000, WALLET ₹0, amount ₹500, key `addmoney-001`.

```text
POST /api/wallet/add-money
Authorization: Bearer <JWT>
Content-Type: application/json
Idempotency-Key: addmoney-001

{ "amount": 500.00 }
```

Deliberately absent: `userId`.

## 5.1 Before the controller (summary of Report 4)

```text
Postman -> Tomcat -> FilterChainProxy -> JwtAuthFilter
   header -> "Bearer " check -> extractClaims (signature + expiry) -> userId, role
   -> findById (user exists) -> ACTIVE -> role matches DB
   -> AuthenticatedUser -> Authentication -> SecurityContext
-> AuthorizationFilter (.authenticated() satisfied)
-> DispatcherServlet -> HandlerMapping (POST /api/wallet/add-money -> WalletController.addMoney)
```

## 5.2 Argument resolution (Spring MVC prepares three parameters)

| Parameter | Supplied by |
|---|---|
| `authenticatedUser` | `@AuthenticationPrincipal` resolver reads the principal from the `SecurityContext` → `AuthenticatedUser(5, email, USER)` |
| `request` | Jackson deserialises `{"amount":500.00}` into `MoneyRequest(amount = BigDecimal 500.00)`; then `@Valid` runs `@NotNull`, `@DecimalMin("0.01")`, `@Digits(8,2)` |
| `clientKey` | `@RequestHeader("Idempotency-Key")` → `"addmoney-001"` |

`BigDecimal` is used for amounts (not `double`), because binary floating point can't represent many decimal fractions exactly.

**Validation failure** (amount 0, -5, `10.123`, 9 integer digits): the controller body never runs; `MethodArgumentNotValidException` → `GlobalExceptionHandler` → 400.

## 5.3 Controller

1. `validateClientKey(clientKey)`: missing/blank/over 64 characters → `InvalidRequestException` → 400.
2. `walletService.addMoney(5, 500.00, "addmoney-001")`.

## 5.4 `WalletServiceImpl.addMoney`

One line: `postMoneyMovement(5, 500.00, "addmoney-001", ADD_MONEY)`.

## 5.5 `LedgerPostingService.postMoneyMovement`

**Entering the method:** `@Transactional` means Spring wraps this bean in a proxy. The proxy gets a DB connection and begins a transaction **before** your method body runs, and will commit when it returns normally or roll back if an unchecked exception escapes. (All custom exceptions extend `RuntimeException`, so they all trigger rollback.)

Because `WalletServiceImpl` calls `ledgerPostingService.postMoneyMovement(...)` on a *different bean*, the call goes through the proxy, so the transaction applies. (A call from one method to another **inside the same class** would bypass the proxy.)

### Step by step with the example

| # | Action | State after |
|---|---|---|
| 1 | `validateInput` passes | |
| 2 | Load user 5, status `ACTIVE` | |
| 3 | `storedKey = "U5-addmoney-001"` | length well under 100 |
| 4 | Lock query returns `[BANK(5000.00), WALLET(0.00)]` ordered by account id; both rows now locked for this transaction | other transactions touching these rows wait |
| 4b | Exactly one BANK, one WALLET | |
| 5 | `ADD_MONEY` → source = BANK, destination = WALLET | |
| 6 | `findByIdempotencyKey("U5-addmoney-001")` → empty | new operation |
| 7 | `5000.00 compareTo 500.00` ≥ 0 | funds OK |
| 8 | BANK → 4500.00, WALLET → 500.00 (set on managed entities; **not yet written to DB**) | |
| 9 | `new Transaction(bankId, walletId, 500.00, ADD_MONEY, COMPLETED, "U5-addmoney-001", null)` | |
| 10 | `saveAndFlush(transaction)` → INSERT now; id (5) available | flush sends all pending changes |
| 11 | Save DEBIT `LedgerEntry(txId 5, BANK, 500.00)` | |
| 12 | Save CREDIT `LedgerEntry(txId 5, WALLET, 500.00)` | |
| End | `buildResult(...)` → `MoneyResult(MoneyResponse(5, ADD_MONEY, 500.00, COMPLETED, 4500.00, 500.00), replayed=false)`; proxy commits; locks released | |

### Two JPA ideas worth owning

- **Dirty checking:** `Account` entities were loaded in this transaction, so Hibernate tracks them. `setBalance(...)` changes are detected at flush and turned into `UPDATE` statements; no explicit `save()` is needed. Note `saveAndFlush(transaction)` flushes the **whole persistence context**, so the two pending account UPDATEs go to the database together with the Transaction INSERT.
- **Flush is not commit.** After `saveAndFlush` the rows exist inside your open transaction, but another transaction can't see them yet, and a later exception still rolls everything back. The real commit happens when the proxy finishes.

### Approximate SQL, in order (confirm in your `show-sql` log)

```sql
select ... from User where user_id = 5;                               -- step 2
select ... from Account where user_id = 5 and account_type in ('BANK','WALLET')
       order by account_id for update;                                 -- step 4 (locks)
select ... from Transaction where idempotency_key = 'U5-addmoney-001'; -- step 6
insert into Transaction (...);                                         -- step 10
update Account set balance = 4500.00 where account_id = <bank>;        -- flushed at step 10
update Account set balance = 500.00  where account_id = <wallet>;
insert into LedgerEntry (...);  -- DEBIT
insert into LedgerEntry (...);  -- CREDIT
commit;
```

## 5.6 Back out

Controller sees `replayed = false` → **201 Created** → Jackson writes `MoneyResponse`:

```json
{ "transactionId": 5, "type": "ADD_MONEY", "amount": 500.00,
  "status": "COMPLETED", "bankBalance": 4500.00, "walletBalance": 500.00 }
```

| Account | Before | After |
|---|---|---|
| BANK | 5,000 | 4,500 |
| WALLET | 0 | 500 |

---

# 6. Runtime trace B: `POST /api/wallet/transfer-to-bank`

Everything up to the service is identical (security, MVC, validation, header). Differences only:

| Stage | Add money | Transfer to bank |
|---|---|---|
| Controller method | `addMoney` | `transferToBank` |
| `WalletServiceImpl` passes | `ADD_MONEY` | `WITHDRAWAL` |
| Source / destination | BANK / WALLET | WALLET / BANK |
| Funds check against | BANK balance | WALLET balance |
| Stored key | `U5-addmoney-001` | `U5-transfer-001` |
| DEBIT ledger entry | BANK | WALLET |
| CREDIT ledger entry | WALLET | BANK |
| Replay / new | 200 / 201 | 200 / 201 |

**Example:** WALLET ₹500, BANK ₹4,500, transfer ₹300:

```text
funds check: 500.00 >= 300.00  OK
WALLET = 500 - 300 = 200.00
BANK   = 4500 + 300 = 4800.00
Transaction #6: WITHDRAWAL, sender = WALLET, receiver = BANK, key "U5-transfer-001"
Ledger: DEBIT WALLET 300, CREDIT BANK 300
-> 201 Created
```

Why a new key `transfer-001` for the second action: it's a different logical operation. Reusing `addmoney-001` here would hit the existing transaction, compare type/sender/receiver, find a mismatch, and return 409.

---

# 7. Idempotency deep dive

## 7.1 The key lifecycle in Aureus

```text
client key "addmoney-001"
   -> + user id  -> storedKey "U5-addmoney-001"
   -> saved on the Transaction row (idempotency_key column, UNIQUE in the DDL)
   -> looked up on every later request with the same key
```

No separate idempotency table: the key lives on the `Transaction`. That's a valid project-level choice; production systems often use a dedicated record storing request fingerprint, status and exact response.

## 7.2 Why the user id is part of the stored key

The column is globally unique. Without the prefix:

- Two customers who both choose `addmoney-001` would collide.
- Worse, a customer could send another customer's key and be handed that customer's transaction (a data leak).

With `U<userId>-...` each user has their own key space. The format is unambiguous: after `U` come the user's digits, then the first `-`.

## 7.3 The three attempts, step by step

| Attempt | Request | Lookup | Outcome | HTTP |
|---|---|---|---|---|
| 1 | key `addmoney-001`, ₹500 | not found | Execute; transaction #5 | 201 |
| 2 | same key, ₹500 | found #5; type, sender, receiver, amount (`compareTo`) all match; status COMPLETED | **Replay**: original result, no money moved, `replayed=true` | 200 |
| 3 | same key, ₹700 | found #5; amount differs | `IdempotencyConflictException`, no change | 409 |

Also: same key but the other endpoint (type differs) → 409; new key `addmoney-002` + ₹500 → a brand-new operation.

## 7.4 Why compare `compareTo` and not `equals`

`new BigDecimal("500.0").equals(new BigDecimal("500.00"))` is `false` (scale differs), while `compareTo` returns 0. Money comparison must use `compareTo`.

## 7.5 Why the key and the money change must be atomic

The key lives on the `Transaction` row, which is inserted in the **same** DB transaction as the balance updates and ledger rows. So there's never a state where "money moved but no key recorded" (retry moves it again) or "key recorded but money never moved" (retry wrongly treated as done).

## 7.6 A failed request does not consume its key

On insufficient funds (or any exception) the whole transaction rolls back: no `Transaction` row, so no stored key. The client may retry the same key later, for example after topping up. (Your `transfer-002` with ₹999,999 returned 422 and leaves that key unused.) APIs differ on this policy; this is a documented choice, not a universal rule.

## 7.7 The replay shows current balances (design note)

On replay, `buildResult(previous, bankAccount, walletAccount, true)` uses the original transaction's id, type, amount and status, but the **current** account balances read under the lock. So a replay after other activity may show different balance numbers than the first response. To replay the *exact* first response you'd store a snapshot of it. Fine for this project; know it for interviews.

## 7.8 What happens without each protection

| Missing | Consequence |
|---|---|
| Idempotency key | A retry after a lost response moves money twice |
| User prefix on the key | Key collisions between users; one user can replay another's transaction |
| Operation comparison | Same key with ₹700 silently returns the ₹500 result; wrong intent accepted |
| Atomic key + money | Key and money can disagree after a crash |
| UNIQUE on `idempotency_key` (it exists in the DDL) | The last line of defence against two inserts of the same key would be gone |

---

# 8. Concurrency, locking and transactions

## 8.1 The problem

BANK = ₹5,000. Two *different* requests (different keys): add ₹4,000 and add ₹3,000.

```text
Without locks:  both read 5000, both pass "5000 >= amount", both debit -> 7,000 leaves a 5,000 account.
```

## 8.2 The fix: `PESSIMISTIC_WRITE` on both accounts

```text
Request A: lock BANK+WALLET -> check 5000>=4000 -> debit -> commit -> unlock
Request B: waits for the lock ........................ then reads BANK=1000 -> 1000>=3000 false -> 422
```

| Detail | Why |
|---|---|
| Lock **both** accounts | The operation changes both; the pair is the protected unit |
| `ORDER BY accountId ASC` | Consistent lock order reduces deadlock risk. It doesn't remove it entirely (other code paths may lock differently) |
| Funds check **after** the lock | The balance you check is the up-to-date, locked one |
| DB locks, not Java `synchronized` | `synchronized` only protects one JVM; the database is the shared truth across instances |
| Lock released at commit/rollback | Held for the whole transaction |

**Difference from registration:** registration used a conditional `UPDATE ... WHERE balance >= :amount` (atomic in one statement). Here you lock rows and check in Java. Both are correct; the lock version is simpler when two rows and several writes are involved.

## 8.3 Idempotency vs concurrency (two different protections)

| | Idempotency | Concurrency control |
|---|---|---|
| Protects against | The same logical request applied twice | Different requests racing on shared state |
| Mechanism | Stored key + comparison | Row locks / transactions |
| Example | Retry after timeout | Two ₹4,000 and ₹3,000 requests at once |

You need both, and they interact in the case below.

## 8.4 ACID in this method

| Property | In Aureus |
|---|---|
| Atomicity | Balances, transaction row and both ledger rows commit together or roll back together |
| Consistency | Funds checked before debit; debit and credit entries of equal amount |
| Isolation | Row locks serialise conflicting money movements (exact isolation level depends on MySQL config, don't assume) |
| Durability | Committed data is durable per the DB configuration |

---

# 9. Error mapping

| Situation | Exception | HTTP |
|---|---|---|
| Amount 0, negative, or too many decimals/digits; null | Bean Validation (controller) or `InvalidRequestException` (service) | 400 |
| Missing/blank/over-long `Idempotency-Key` | `InvalidRequestException` | 400 |
| Inactive or missing user | `InvalidRequestException` | 400 |
| BANK or WALLET missing | `AccountNotFoundException` | 404 |
| Same key, different operation | `IdempotencyConflictException` | 409 |
| Source balance too low | `InsufficientFundsException` | 422 |
| No / invalid / expired JWT | Security entry point | 401 |
| New transaction created | n/a | 201 |
| Replay of an existing transaction | n/a | 200 |

(Mappings follow the handlers set up for this feature; 409 and 422 were observed in your tests.)

---

# 10. What you tested, and what is still untested

## 10.1 Observed in Postman (user id 5)

| Step | Request | Result |
|---|---|---|
| 1 | Register | BANK ₹5,000, WALLET ₹0 |
| 2 | Add ₹500, key `addmoney-001` | transactionId 5, BANK 4500, WALLET 500 |
| 3 | Same request again | same transactionId 5, balances unchanged |
| 4 | Same key, ₹700 | 409 |
| 5 | Transfer ₹300 to bank, key `transfer-001` | transactionId 6, BANK 4800, WALLET 200 |
| 6 | Transfer ₹999,999, key `transfer-002` | 422 |

Arithmetic check: 5000 − 500 + 300 = 4800, and 0 + 500 − 300 = 200. The replay and the failed request changed nothing.

## 10.2 Still unverified

| Item | How to check |
|---|---|
| Ledger rows really in MySQL (4 rows for tx 5 and 6) | `SELECT * FROM LedgerEntry WHERE transaction_id IN (5,6)`; expect DEBIT BANK 500, CREDIT WALLET 500, DEBIT WALLET 300, CREDIT BANK 300 |
| Exactly 2 transactions, none for the replay | `SELECT * FROM Transaction WHERE idempotency_key LIKE 'U5-%'` |
| Debits equal credits overall | `SELECT SUM(CASE WHEN entry_type='DEBIT' THEN amount END), SUM(CASE WHEN entry_type='CREDIT' THEN amount END) FROM LedgerEntry` |
| Conservation (no money created) | `SELECT SUM(balance) FROM Account`, same before and after money-movement tests |
| UNIQUE on `idempotency_key` | `SHOW INDEX FROM Transaction` (the Day 2 DDL declares it UNIQUE) |
| Concurrency (double-spend, opposite transfers, duplicate retries) | Day 8 automated tests |
| Missing header, 70-character key, no token, another user using the same key | Quick Postman checks |

---

# 11. Review findings

## 11.1 One real issue to fix: the snapshot ordering (duplicates in flight)

**Where:** inside `postMoneyMovement`, the order of steps 2, 4 and 6.

**Current order:**
```text
2. userRepository.findById(...)              <- a plain SELECT
4. findCustomerAccountsForUpdate(...)        <- SELECT ... FOR UPDATE (waits for the lock)
6. findByIdempotencyKey(...)                 <- a plain SELECT
```

**What I believe happens (I have not run it):** MySQL's default isolation level (REPEATABLE READ) fixes a data "snapshot" at the transaction's **first plain SELECT**, which here is step 2, *before* the code waits for the lock. A locking read (step 4) sees the latest committed data, but the plain SELECT at step 6 still reads from the older snapshot.

Now two identical requests arrive together (a client retrying while the first is still running):

```text
A and B both take their snapshot at step 2
A gets the lock, posts, commits
B gets the lock (fresh balances), then looks up the key using its OLD snapshot -> does not see A's row
B continues as if new -> funds check -> UPDATE -> INSERT Transaction with the same key
-> UNIQUE(idempotency_key) violation -> B rolls back
```

**Impact:** money is still safe, because the UNIQUE constraint and rollback stop the second posting. But B returns an error (likely a 500) instead of the clean 200 replay it should get.

**Fix (tiny):** do the locking read **first**, so the snapshot is taken after the lock is held. Move STEP 4 (lock) above STEP 2 (user check). Then `findById` and the idempotency lookup both read fresh data, and B sees A's committed row and replays.

```text
validateInput
lock accounts (FOR UPDATE)    <- first DB statement
load user, check ACTIVE
build/check idempotency key and replay
funds check ... as before
```

(Alternative: catch `DataIntegrityViolationException` in the non-transactional `WalletServiceImpl` and re-run the lookup in a fresh transaction.)

**Confirm it:** the Day 8 duplicate-retry test (10 threads, same key) will show it. Expected before the fix: one 201 and the rest errors; after the fix: one 201 and nine 200 replays, with exactly one transaction row.

## 11.2 Notes (not bugs)

- **Replay shows current balances**, not the original snapshot (section 7.7).
- **Cosmetic:** `LedgerEntry` is referenced by its full package name in the service because it isn't in the import list. It works; adding the import tidies it.

## 11.3 Where Aureus differs from a production platform (so you don't overclaim)

| Area | Aureus now | Production extension |
|---|---|---|
| Idempotency storage | Key on the Transaction row | Dedicated record with request fingerprint, status, exact response, retention |
| Ledger | Debit and credit entries written; balances updated directly | Append-only ledger with balances derived or reconciled from it |
| Lifecycle | Success stored as COMPLETED, failures roll back | Full state machine for async work |
| Money movement | Internal BANK ↔ WALLET | External rails, webhooks (idempotent consumers) |
| Failure recovery | DB rollback | Outbox pattern, provider reconciliation |

---

# 12. Interview questions

**Q1. What is idempotency and why does a wallet need it?**
Repeating the same logical request has no extra effect after the first success. Responses get lost, so clients retry; without an idempotency key a retried add-money could move the money twice.

**Q2. Why must the client generate the key?**
Only the client knows whether it's a retry or a new action. Two ₹500 deposits can both be legitimate.

**Q3. Same key, different amount?**
Rejected as conflicting reuse: `IdempotencyConflictException` → 409. Never silently execute the new amount or return the old result.

**Q4. Why prefix the stored key with the user id?**
Keys are globally unique in the table. The prefix gives each user their own key space and stops one user from reading another's transaction by sending their key.

**Q5. Is idempotency exactly-once?**
No. Delivery can repeat; idempotency makes repeats safe.

**Q6. Why check idempotency after taking the lock?**
So concurrent requests for the same accounts are serialised first; the second one then finds the first one's committed transaction. (Detail: do the locking read before any plain read, see 11.1.)

**Q7. Why lock both accounts, and why order them by id?**
The operation changes both rows. A consistent lock order lowers deadlock risk, but doesn't eliminate it.

**Q8. Pessimistic vs optimistic locking?**
Pessimistic takes DB locks so conflicting writers wait (used here for money). Optimistic detects conflicts at update time with a version column and retries.

**Q9. Why not `synchronized`?**
It only coordinates threads in one JVM. Multiple instances share the database, so the lock must be in the database.

**Q10. What does `@Transactional` do here?**
A proxy begins a DB transaction before the method and commits on normal return or rolls back on an unchecked exception. Balances, transaction row and ledger rows succeed or fail together. It works because the call comes from another bean through the proxy.

**Q11. `save` vs `saveAndFlush`, and flush vs commit?**
`saveAndFlush` sends pending SQL to the DB immediately (and gives the generated id). Flush isn't commit: rows are still inside the open transaction and can roll back.

**Q12. How do the account balances get saved without calling `save`?**
Dirty checking: managed entities are tracked; changed fields become UPDATEs at flush.

**Q13. Why a Transaction row *and* ledger entries?**
The transaction says what happened; the ledger entries show the effect on each account (one DEBIT and one CREDIT of equal amount).

**Q14. Why `BigDecimal` and `compareTo`?**
Exact decimal arithmetic for money; `equals` treats 500.0 and 500.00 as different, `compareTo` doesn't.

**Q15. Why does the replay return 200 and a new transaction 201?**
201 = a resource was created; a replay creates nothing, it returns the existing one.

**Q16. What if the server commits but the response is lost?**
The client retries with the same key; the server finds the committed transaction and returns it without moving money again.

**Q17. Does a failed request use up the key?**
No, everything rolled back, so no row holds the key. The client may retry it.

**Q18. What would you improve for production?**
Dedicated idempotency records with request fingerprint and exact stored response, append-only ledger with reconciliation, retry-on-deadlock handling, metrics for replays, conflicts and lock waits.

---

# 13. Revision sheet and self-test

## 13.1 Add money in one flow

```text
Postman -> Tomcat -> Security filters -> JwtAuthFilter -> SecurityContext
-> DispatcherServlet -> WalletController.addMoney
     @AuthenticationPrincipal, @Valid MoneyRequest, Idempotency-Key header
     validateClientKey
-> WalletServiceImpl.addMoney -> postMoneyMovement(..., ADD_MONEY)
-> [proxy: BEGIN transaction]
     validateInput
     (recommended: lock BANK+WALLET FOR UPDATE first)
     load user, ACTIVE
     storedKey = U<id>-<key>
     lock BANK + WALLET (ordered by id)
     source = BANK, destination = WALLET
     findByIdempotencyKey
        found + same operation + COMPLETED -> replay (200)
        found + different                  -> 409
     funds check on BANK
     BANK -= amount, WALLET += amount          (dirty checking)
     Transaction(COMPLETED, storedKey)  saveAndFlush
     LedgerEntry DEBIT BANK, LedgerEntry CREDIT WALLET
     buildResult(replayed=false)
-> [proxy: COMMIT, release locks]
-> 201 Created + MoneyResponse JSON
```

Transfer to bank: same, with `WITHDRAWAL`, source = WALLET, destination = BANK.

## 13.2 Self-test: explain each without looking

- [ ] Why the controller never accepts a `userId`
- [ ] Why `required = false` plus `validateClientKey` on the header
- [ ] What `MoneyResult.replayed` controls
- [ ] Why `WalletServiceImpl` has no money logic
- [ ] What the transactional proxy does and why the call must come from another bean
- [ ] What the stored key looks like and why the user id is in it
- [ ] The three idempotency outcomes (execute, replay, 409)
- [ ] Why `compareTo` and not `equals`
- [ ] Why the funds check is after the lock
- [ ] How dirty checking persists the balances
- [ ] Flush vs commit
- [ ] Why two ledger entries per transaction
- [ ] Why a failed request leaves the key reusable
- [ ] Idempotency vs concurrency control
- [ ] The snapshot ordering issue and its fix

---

# 14. Next steps

1. **Today/now:** apply the reorder fix in 11.1 (move the account lock above the user lookup), then run the SQL checks in 10.2. About 15 minutes.
2. **Quick Postman checks:** missing header, 70-character key, no token, the same key from a second user.
3. **Day 8 (Send Money):** reuse `postMoneyMovement` with type `TRANSFER` and the recipient's WALLET as destination; this will need a variant that locks **two different users' accounts** in ascending id order. Then the automated tests: ₹1,000 wallet with two ₹700 sends (exactly one succeeds), opposite transfers A→B and B→A (no deadlock), 10 threads with one key (exactly one transaction, nine replays).

---

# Appendix A: `LedgerPostingService` (as written)

```java
@Service
public class LedgerPostingService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final UserRepository userRepository;

    public LedgerPostingService(AccountRepository accountRepository,
                                TransactionRepository transactionRepository,
                                LedgerEntryRepository ledgerEntryRepository,
                                UserRepository userRepository) { ... }

    @Transactional
    public MoneyResult postMoneyMovement(Integer userId, BigDecimal amount,
                                         String clientKey, TransactionType transactionType) {

        // STEP 1: Validate the incoming operation.
        validateInput(userId, amount, clientKey, transactionType);

        // STEP 2: Verify that the authenticated user is active.
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new InvalidRequestException("Authenticated user does not exist"));
        if (user.getUserStatus() != UserStatus.ACTIVE) {
            throw new InvalidRequestException("User account is not active");
        }

        // STEP 3: Construct the backend idempotency key.
        String storedKey = "U" + userId + "-" + clientKey;
        if (storedKey.length() > 100) {
            throw new InvalidRequestException("Generated idempotency key exceeds database limit");
        }

        // STEP 4: Lock both customer accounts (repository orders by account ID).
        List<Account> accounts = accountRepository.findCustomerAccountsForUpdate(
                userId, List.of(AccountType.BANK, AccountType.WALLET));

        Account bankAccount = null;
        Account walletAccount = null;
        for (Account account : accounts) {
            if (account.getAccountType() == AccountType.BANK) {
                if (bankAccount != null) throw new InvalidRequestException("Multiple BANK accounts found for user");
                bankAccount = account;
            } else if (account.getAccountType() == AccountType.WALLET) {
                if (walletAccount != null) throw new InvalidRequestException("Multiple WALLET accounts found for user");
                walletAccount = account;
            }
        }
        if (bankAccount == null)   throw new AccountNotFoundException("BANK account not found");
        if (walletAccount == null) throw new AccountNotFoundException("WALLET account not found");

        // STEP 5: Determine source and destination accounts.
        Account sourceAccount;
        Account destinationAccount;
        if (transactionType == TransactionType.ADD_MONEY) {
            sourceAccount = bankAccount;  destinationAccount = walletAccount;
        } else if (transactionType == TransactionType.WITHDRAWAL) {
            sourceAccount = walletAccount; destinationAccount = bankAccount;
        } else {
            throw new InvalidRequestException("Unsupported wallet transaction type");
        }

        // STEP 6: Check idempotency AFTER acquiring account locks.
        Optional<Transaction> existingTransaction = transactionRepository.findByIdempotencyKey(storedKey);
        if (existingTransaction.isPresent()) {
            Transaction previous = existingTransaction.get();

            boolean sameOperation =
                    previous.getTransactionType() == transactionType
                    && previous.getSenderAccountId().equals(sourceAccount.getAccountId())
                    && previous.getReceiverAccountId().equals(destinationAccount.getAccountId())
                    && previous.getAmount().compareTo(amount) == 0;

            if (!sameOperation || previous.getTransactionStatus() != TransactionStatus.COMPLETED) {
                throw new IdempotencyConflictException(
                        "Idempotency key has already been used for a different transaction");
            }
            return buildResult(previous, bankAccount, walletAccount, true);   // replay
        }

        // STEP 7: Verify sufficient funds.
        if (sourceAccount.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException("Insufficient balance in source account");
        }

        // STEP 8: Update both balances (managed entities; dirty checking persists them).
        sourceAccount.setBalance(sourceAccount.getBalance().subtract(amount));
        destinationAccount.setBalance(destinationAccount.getBalance().add(amount));

        // STEP 9: Create the completed transaction.
        Transaction transaction = new Transaction(
                sourceAccount.getAccountId(), destinationAccount.getAccountId(),
                amount, transactionType, TransactionStatus.COMPLETED, storedKey, null);
        Transaction savedTransaction = transactionRepository.saveAndFlush(transaction);

        // STEP 10: DEBIT ledger entry.
        ledgerEntryRepository.save(new LedgerEntry(
                savedTransaction.getTransactionId(), sourceAccount.getAccountId(),
                EntryType.DEBIT, amount));

        // STEP 11: CREDIT ledger entry.
        ledgerEntryRepository.save(new LedgerEntry(
                savedTransaction.getTransactionId(), destinationAccount.getAccountId(),
                EntryType.CREDIT, amount));

        // STEP 12: Build the response using updated balances.
        return buildResult(savedTransaction, bankAccount, walletAccount, false);
    }

    private void validateInput(...) { /* userId, amount > 0, scale <= 2, integer digits <= 8,
                                         key non-blank and <= 64, type non-null */ }

    private MoneyResult buildResult(Transaction t, Account bank, Account wallet, boolean replayed) {
        return new MoneyResult(new MoneyResponse(t.getTransactionId(), t.getTransactionType(),
                t.getAmount(), t.getTransactionStatus(), bank.getBalance(), wallet.getBalance()), replayed);
    }
}
```

(Some lines abridged with `...` for readability; the unabridged source is in your project.)

**End of Report 5.**