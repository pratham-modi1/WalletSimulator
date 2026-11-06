# Aureus: Day 1 Report
## Project Understanding

**Tagline:** Where Money Meets Elegance!
**Type:** Digital wallet and payments backend
**Primary focus:** End-to-end backend development with Java and Spring Boot
**Environment:** Fully simulated. No real money, bank, UPI or payment gateway
**Frontend:** Desktop web app, built only after the backend is complete

---

## 1. What is Aureus?

A simulated digital wallet inspired by Paytm and PhonePe. Users can:

- Register and log in
- Get a simulated bank account (₹5,000 demo funds) and a wallet (₹0)
- Add money from bank to wallet
- Send money to other registered users
- Transfer money from wallet back to bank
- View transaction history
- Create savings goals

The real purpose is not moving numbers. It is demonstrating a **secure, reliable and consistent financial backend**: every operation follows business rules, updates balances correctly, keeps a verifiable ledger, and fails safely.

## 2. Why build it?

A CRUD app teaches save/read/update/delete. Aureus forces a backend where many parts work together and correctness matters. What it teaches:

- REST APIs with Spring Boot
- Relational design in MySQL
- Authentication and authorization (Spring Security, JWT)
- Business logic and request validation
- Financial transactions and double-entry ledger concepts
- Concurrency control (no overspending via simultaneous requests)
- Idempotency (no duplicate operations on retry)
- Error handling, auditability, testing, debugging
- The full request flow from HTTP to database and back

Goal: understand how a backend works internally, not just make endpoints return 200.

## 3. Simulated banking model

On successful registration:

- A simulated bank account is created with **₹5,000**
- A wallet is created with **₹0**
- The ₹5,000 is recorded in the ledger as an `OPENING_BALANCE` transaction from the internal SYSTEM account
- The user chooses how much to move from bank to wallet

Example: transfer ₹3,000

| Account | Before | After |
|---|---:|---:|
| Bank | ₹5,000 | ₹2,000 |
| Wallet | ₹0 | ₹3,000 |

Total stays ₹5,000; money only moves between accounts.

**Rules**

1. Demo balance is initialised once, at registration
2. Login, refresh or retry must never create more money
3. No transfer above available balance
4. No negative balances
5. Every financial movement is recorded
6. Bank-to-wallet and wallet-to-bank are internal simulated transfers

The ₹5,000 is a project assumption, not a banking requirement.

## 4. Core features

| # | Feature | Summary |
|---|---|---|
| 1 | Registration and authentication | Validate details, reject duplicates, BCrypt hashing, signed JWT on login, creates bank account and wallet |
| 2 | Account dashboard | View bank balance, wallet balance, recent activity. Own data only; identity comes from the token, not client-supplied IDs |
| 3 | Add money | Bank to wallet. Checks amount, balance, limits. All changes roll back on failure |
| 4 | Send money | Wallet to another user's wallet via unique ID or email. Atomic: both sides succeed or neither |
| 5 | Transfer to bank | Wallet to own simulated bank. Not a real withdrawal |
| 6 | Transaction history | Add, send, receive, transfer-back records with amount, date, status, reference ID. Filtering and pagination. Private to the owner |
| 7 | Savings goals (v1) | Tracking only. Calculates remaining amount and suggested monthly saving. Does not reserve, move or earn money |
| 8 | Limits and risk rules | Positive amounts, configured limits, sufficient funds, max wallet balance, invalid recipient rejection, basic risk events. Rule-based, no ML |
| 9 | Admin, audit, reconciliation | Admin role, audited sensitive actions, checks that ledger entries balance and stored balances match records. Corrections via reversals only |

**Savings example:** target ₹10,000, wallet ₹5,000, 5 months left gives (10,000 − 5,000) ÷ 5 = ₹1,000/month. It is only a calculation, not an affordability guarantee.

## 5. User stories (acceptance criteria summary)

1. **Register and log in:** validated input, duplicate email rejected, BCrypt, bank ₹5,000 and wallet ₹0 created once, opening funds recorded, JWT on login, protected endpoints reject missing or invalid tokens
2. **View balances:** user sees own bank and wallet; identity from authentication; no access to others' accounts
3. **Add money:** validated amount and balance, atomic update, balanced ledger entries, no partial updates, retry does not double-add
4. **Send money:** recipient exists and is eligible, sufficient funds, debit + credit + transaction + ledger commit together, no self-send, simultaneous requests cannot double-spend
5. **Transfer to bank:** sufficient wallet funds, atomic update, ledgered, retry-safe
6. **Review transactions:** unique references, pagination and filters, authorised records only, completed transactions immutable
7. **Savings goal:** manage own goals, calculations use latest wallet balance, no balance changes or ledger entries
8. **Verify system (admin):** admin-only operations, audited sensitive actions, reconciliation detects unbalanced entries or inconsistent balances, fixes via controlled procedures

## 6. The ledger

The ledger is the auditable record of why each balance has its value.

Example, Aarav sends ₹500 to Priya:

| Entry | Amount |
|---|---:|
| Aarav wallet: debit | ₹500 |
| Priya wallet: credit | ₹500 |
| Total debit = total credit | ₹500 |

**Principles**

- Each operation has a unique reference
- Every transaction has proper debit and credit entries; totals balance
- Account updates and ledger entries commit in one DB transaction
- Completed records are immutable; corrections use reversals or compensating entries
- Reconciliation checks records against stored balances

## 7. Security, idempotency, concurrency

**JWT flow:** register or log in, backend validates credentials, issues signed JWT, client sends `Authorization: Bearer <token>`, Spring Security validates it and sets identity, backend checks ownership and role. JWT does not replace authorization.

**Idempotency:** if a ₹500 send times out and the client retries, the retry must not send twice. Aureus accepts a unique idempotency key, records key and result, returns the original outcome on retry, rejects the same key for a different operation, and handles simultaneous retries safely.

**Concurrency:** with ₹1,000 and two simultaneous ₹700 sends, only one may succeed. Use DB transactions with locking or conditional updates. Debit, credit, transaction record and ledger entries commit or roll back together.

## 8. Tech stack

| Technology | Purpose |
|---|---|
| Java | Main language |
| Spring Boot | Application framework |
| Spring MVC | REST handling |
| Spring Data JPA / Hibernate | Persistence and ORM |
| MySQL | Relational database |
| Spring Security + JWT | Authentication and authorization |
| BCrypt | Password hashing |
| Bean Validation | Request validation |
| Maven | Build and dependencies |
| Postman | Manual API testing |
| JUnit 5, Mockito | Unit tests |
| Testcontainers | Realistic DB integration tests |
| Swagger/OpenAPI | API docs |
| Flyway | Versioned DB migrations |
| Docker | Packaging and deployment |

Architecture: **modular monolith**, not microservices.

## 9. Design principles

1. Never create money silently
2. Never allow negative balances
3. Make financial operations atomic
4. Keep a verifiable ledger
5. Protect against retries with idempotency
6. Validate every request server-side
7. Enforce ownership and permissions
8. Keep completed records immutable; use reversals
9. Keep savings goals separate from money movement
10. Test failure cases, not just success
11. Keep the system understandable
12. Build incrementally

## 10. Development workflow

**Phase 1: Overall database schema.** Entities, relationships, keys, constraints, uniqueness, and how balances, transactions and ledger stay consistent.

**Phase 2: One feature at a time, as a vertical slice.**
1. Understand the user journey
2. Define the REST interface (endpoint, method, DTOs, errors)
3. Controller first
4. Add security and validation
5. Service (business logic)
6. Repository and persistence
7. Trace the full flow end to end
8. Test in Postman (success, failure, edge cases, authorization)
9. Verify the database (balances, transactions, ledger)
10. Move on only after understanding and testing

Request flow: **Postman → Controller → Service → Repository → MySQL**, and back. Not all controllers, then all services, then all repositories.

**Phase 3: Test and strengthen.** Transfers, insufficient funds, invalid and expired JWTs, duplicate idempotency keys, concurrent transfers, ledger balancing, reconciliation, access control.

**Phase 4: Docs, deployment, frontend.** API docs, automated tests, Docker, deploy, then the frontend: minimal premium banking dashboard, dark charcoal with restrained gold accents, account overview on the homepage, separate pages for transactions, savings and account management.

## 11. Final project goal

Aureus is a simulated wallet that demonstrates end-to-end backend engineering: how requests become validated business operations, how those operations interact with the database, how financial correctness is maintained, and how errors are handled.

> The goal is not just a wallet that works in a demo. It is to understand why a backend works correctly, what can go wrong, and how to prevent it.

## 12. Decision recorded at the end of Day 1/2

The internal **SYSTEM account** is kept. The developer (you, as admin) owns it, no one else has access, and it is the counterparty that funds each user's ₹5,000 opening balance.
