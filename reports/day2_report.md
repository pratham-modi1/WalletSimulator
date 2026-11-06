# Aureus: Day 2 Report
## Database Schema (DDL) and Why Each Decision Was Made

**Database:** MySQL 8.0.16 or newer (CHECK constraints are enforced from this version)
**Engine:** InnoDB
**Tables (5):** User, Account, Transaction, LedgerEntry, Savings
**Status:** Finalised. No further DDL changes planned.

---

# Part 1: Final DDL

```sql
CREATE DATABASE IF NOT EXISTS aureus;

USE aureus;


-- =====================================================
-- 1. USER
-- =====================================================

CREATE TABLE `User` (
    user_id INT PRIMARY KEY AUTO_INCREMENT,

    email VARCHAR(100) NOT NULL UNIQUE,

    password_hash VARCHAR(255) NOT NULL,

    role VARCHAR(5) NOT NULL
        CHECK (role IN ('USER', 'ADMIN')),

    user_status VARCHAR(7) NOT NULL
        CHECK (user_status IN ('ACTIVE', 'DELETED'))
) ENGINE = InnoDB;


-- =====================================================
-- 2. ACCOUNT
-- =====================================================

CREATE TABLE Account (
    account_id INT PRIMARY KEY AUTO_INCREMENT,

    user_id INT NOT NULL,

    account_type VARCHAR(6) NOT NULL
        CHECK (
            account_type IN ('BANK', 'WALLET', 'SYSTEM')
        ),

    balance DECIMAL(10,2) NOT NULL
        CHECK (balance >= 0),

    CONSTRAINT uq_user_account_type
        UNIQUE (user_id, account_type),

    CONSTRAINT fk_account_user
        FOREIGN KEY (user_id)
        REFERENCES `User`(user_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT
) ENGINE = InnoDB;


-- =====================================================
-- 3. TRANSACTION
-- =====================================================

CREATE TABLE `Transaction` (
    transaction_id INT PRIMARY KEY AUTO_INCREMENT,

    sender_account_id INT NOT NULL,

    receiver_account_id INT NOT NULL,

    amount DECIMAL(10,2) NOT NULL
        CHECK (amount > 0),

    transaction_type VARCHAR(15) NOT NULL
        CHECK (
            transaction_type IN (
                'ADD_MONEY',
                'TRANSFER',
                'WITHDRAWAL',
                'REVERSAL',
                'OPENING_BALANCE'
            )
        ),

    transaction_date_time TIMESTAMP NOT NULL
        DEFAULT CURRENT_TIMESTAMP,

    transaction_status VARCHAR(10) NOT NULL
        CHECK (
            transaction_status IN (
                'PENDING',
                'COMPLETED',
                'FAILED'
            )
        ),

    idempotency_key VARCHAR(100) NOT NULL UNIQUE,

    original_transaction_id INT NULL,

    CONSTRAINT uq_original_transaction
        UNIQUE (original_transaction_id),

    CONSTRAINT chk_different_accounts
        CHECK (sender_account_id <> receiver_account_id),

    CONSTRAINT chk_reversal_reference
        CHECK (
            (
                transaction_type = 'REVERSAL'
                AND original_transaction_id IS NOT NULL
            )
            OR
            (
                transaction_type <> 'REVERSAL'
                AND original_transaction_id IS NULL
            )
        ),

    CONSTRAINT fk_transaction_sender
        FOREIGN KEY (sender_account_id)
        REFERENCES Account(account_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_transaction_receiver
        FOREIGN KEY (receiver_account_id)
        REFERENCES Account(account_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_original_transaction
        FOREIGN KEY (original_transaction_id)
        REFERENCES `Transaction`(transaction_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT
) ENGINE = InnoDB;


-- =====================================================
-- 4. LEDGER ENTRY
-- =====================================================

CREATE TABLE LedgerEntry (
    ledger_entry_id INT PRIMARY KEY AUTO_INCREMENT,

    transaction_id INT NOT NULL,

    account_id INT NOT NULL,

    entry_type VARCHAR(6) NOT NULL
        CHECK (entry_type IN ('DEBIT', 'CREDIT')),

    amount DECIMAL(10,2) NOT NULL
        CHECK (amount > 0),

    created_at TIMESTAMP NOT NULL
        DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_ledger_transaction
        FOREIGN KEY (transaction_id)
        REFERENCES `Transaction`(transaction_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT,

    CONSTRAINT fk_ledger_account
        FOREIGN KEY (account_id)
        REFERENCES Account(account_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT
) ENGINE = InnoDB;


-- =====================================================
-- 5. SAVINGS
-- =====================================================

CREATE TABLE Savings (
    goal_id INT PRIMARY KEY AUTO_INCREMENT,

    user_id INT NOT NULL,

    goal_name VARCHAR(100) NOT NULL,

    target_amount DECIMAL(10,2) NOT NULL
        CHECK (target_amount > 0),

    deadline DATE NOT NULL,

    goal_status VARCHAR(10) NOT NULL
        CHECK (
            goal_status IN (
                'ACTIVE',
                'COMPLETED',
                'PAUSED',
                'CANCELLED'
            )
        ),

    CONSTRAINT fk_savings_user
        FOREIGN KEY (user_id)
        REFERENCES `User`(user_id)
        ON DELETE RESTRICT
        ON UPDATE RESTRICT
) ENGINE = InnoDB;
```

---

# Part 2: Decisions and reasons, table by table

## 1. User

| Decision | Reason |
|---|---|
| `user_id INT AUTO_INCREMENT` | Database-generated unique ID |
| `email NOT NULL UNIQUE` | Every user has an email; duplicates blocked. A deleted user's email stays reserved |
| `password_hash VARCHAR(255)` | Store a hash (BCrypt), never plaintext |
| `role` in (`USER`, `ADMIN`) | Role-based access without a separate role table for just two roles |
| `user_status` in (`ACTIVE`, `DELETED`) | Soft deletion keeps financial history intact |

**Why soft delete:** a user is never physically removed; the status becomes `DELETED`. The app blocks login and new transactions, while historic transactions and ledger entries stay available for audit. The schema does not enforce the login block; auth and service layers must check the status.

## 2. Account

| Decision | Reason |
|---|---|
| `account_id` primary key | Stable account identifier |
| `user_id` foreign key | Links the account to its owner |
| `account_type` in (`BANK`, `WALLET`, `SYSTEM`) | Distinguishes bank, wallet and the internal counter-account |
| `UNIQUE(user_id, account_type)` | One BANK and one WALLET per user at most |
| `balance DECIMAL(10,2)` | Exact decimal arithmetic for rupees, no floating point |
| `CHECK (balance >= 0)` | Stored balances can never go negative |

`DECIMAL(10,2)` holds up to ₹99,999,999.99. Enough for this project.

### Why SYSTEM was added

Opening balances are recorded as `OPENING_BALANCE` transactions so the ledger stays balanced and no money appears without explanation. A balanced entry needs a counterparty:

- Sender: internal SYSTEM account
- Receiver: the customer's BANK account
- Ledger: matching debit and credit entries

It sits inside the existing Account table, so there is no sixth table.

**Decision (Day 2):** you keep the SYSTEM account. It belongs to your own admin user, no one else has access to it, and it is seeded with a very large balance.

**Seeding note:** because of `DECIMAL(10,2)` and `CHECK (balance >= 0)`, the seed value must be at most **99999999.99**. Seeding `9999999999` will fail (out of range). At ₹5,000 per registration, ₹99,999,999.99 funds about 20,000 users, which is plenty for a demo. Alternatively, skip decrementing the SYSTEM balance and write only its ledger entries.

Regular registration (done in business logic, not DDL) creates a BANK account with ₹5,000 and a WALLET account with ₹0.

## 3. Transaction

The central record of each financial operation.

| Decision | Reason |
|---|---|
| Sender and receiver account IDs | One row captures both sides of a movement |
| `amount DECIMAL(10,2)`, `CHECK (amount > 0)` | Money movements must be positive |
| Five transaction types | Add money, send money, withdraw, reverse, opening balance |
| `transaction_date_time` default | Auto-records creation time |
| Three statuses (`PENDING`, `COMPLETED`, `FAILED`) | Room for pending operations; completed and failed outcomes preserved |
| Globally unique `idempotency_key` | No two rows share a request key |
| Nullable `original_transaction_id` | Links a reversal to the transaction it reverses |
| `UNIQUE(original_transaction_id)` | A transaction can be reversed at most once |

### Why `TRANSFER` and not separate `SEND` / `RECEIVE`

One transfer is recorded once. If Pratham sends Rahul ₹500, Pratham sees `−₹500, sent to Rahul` and Rahul sees `+₹500, received from Pratham`. The application derives the display from the sender and receiver IDs.

### Why the same-account check

```sql
CHECK (sender_account_id <> receiver_account_id)
```

Blocks an account sending money to itself. A user's BANK to WALLET move is still allowed because those are different accounts.

### Why reversals link to originals

A reversal never overwrites the original; it is a new row pointing at the original via `original_transaction_id`. The unique constraint prevents two reversals of the same transaction. The CHECK ensures a `REVERSAL` row must have an original and other types must not. Whether the original is eligible for reversal is checked in the service layer.

### Why keep the idempotency key

If the server processes a ₹500 transfer but the response is lost, the client may retry. The same key lets the backend recognise a retry instead of executing twice. The unique constraint stops duplicate keys; the service must still verify the retried request matches the original.

## 4. LedgerEntry

| Decision | Reason |
|---|---|
| Separate from Transaction | Transaction describes the operation; ledger entries record each account movement |
| `transaction_id` foreign key | Every entry belongs to a transaction |
| `account_id` foreign key | Identifies the debited or credited account |
| `entry_type` in (`DEBIT`, `CREDIT`) | Direction of the movement |
| Positive `amount` | Direction comes from entry type, never a negative amount |
| `created_at` default | Records when the row was created |

A regular ₹500 wallet transfer has one ₹500 debit (sender) and one ₹500 credit (receiver).

**Limitation:** the DDL cannot ensure every completed transaction has exactly two ledger entries or that debits equal credits. Row-level CHECKs cannot validate aggregates across rows. The service layer enforces this inside a database transaction and rolls back on failure.

## 5. Savings

| Decision | Reason |
|---|---|
| `goal_id` primary key | Unique goal identifier |
| `user_id` foreign key | Each goal belongs to a user |
| `goal_name`, `target_amount` | What the user is saving for, and how much |
| `deadline` | Intended completion date |
| `goal_status` (`ACTIVE`, `COMPLETED`, `PAUSED`, `CANCELLED`) | Lifecycle of a goal |

Savings goals are **tracking-only** in version 1: no reserved money, no transfers, no interest. Remaining amount is calculated from the target and the latest wallet balance. Goal names are deliberately not unique per user.

---

# Part 3: Foreign keys and deletion rules

Every foreign key uses `ON DELETE RESTRICT` and `ON UPDATE RESTRICT`.

| Relationship | Why it exists |
|---|---|
| `Account.user_id → User.user_id` | Account must reference an existing user |
| `Savings.user_id → User.user_id` | Goal must reference an existing user |
| `Transaction.sender_account_id → Account.account_id` | Sending account must exist |
| `Transaction.receiver_account_id → Account.account_id` | Receiving account must exist |
| `LedgerEntry.transaction_id → Transaction.transaction_id` | Entry must belong to an existing transaction |
| `LedgerEntry.account_id → Account.account_id` | Entry must reference an existing account |
| `Transaction.original_transaction_id → Transaction.transaction_id` | Reversal must refer to an existing transaction |

`RESTRICT` blocks deleting or re-keying a referenced row while dependents exist. Cascades are avoided because financial history must not disappear when a user is deleted.

Primary keys and unique constraints already create indexes. No extra query-performance indexes were added.

---

# Part 4: What was fixed, added, dropped or deferred

## Dropped or deliberately not included

| Item | Reason |
|---|---|
| Separate `SEND` and `RECEIVE` types | One transfer should be one record |
| Separate role table | Only two roles; not worth a table |
| Cascading deletes | Would destroy financial history |
| Extra query indexes | Deferred until query patterns are known |
| Database triggers for immutability | Service-layer rules come first; add triggers if DB-level enforcement is needed |
| `updated_at` / `deleted_at` columns | No concrete audit or reporting need yet |
| Unique goal names per user | Users may have two goals with the same name |

## Enforced in the service layer, not in DDL

| Rule | Why the DDL cannot enforce it |
|---|---|
| Valid account-type combinations per transaction type (e.g. `ADD_MONEY` is BANK → WALLET, `TRANSFER` is WALLET → WALLET) | CHECKs cannot query other rows |
| Sender and receiver belong to different users | Needs account-to-user lookup |
| `PENDING` status lifecycle | DDL restricts values, not transitions |
| Exactly two ledger entries per completed transaction | CHECK cannot count other rows |
| Debit total equals credit total | Needs multiple-row validation, atomic posting |
| Immutability of completed records | No triggers added |
| Reversal eligibility (not already a reversal, valid status and type) | Requires inspecting the referenced transaction |
| Idempotency payload matching | Unique key blocks duplicate keys only; Java compares request contents |
| Concurrency and simultaneous transfers | InnoDB transactions, row locking, rollback in service layer |
| Auto-creation of accounts and opening transaction | Application workflow, not table definition |
| Login block for `DELETED` users | Auth and service layers |

---

# Part 5: Setup and implementation notes

1. **Seed data:** one admin user (`role = ADMIN`) plus its `SYSTEM` account with balance ≤ 99999999.99.
2. **Opening balance flow:** on registration, create BANK (₹5,000) and WALLET (₹0), then record an `OPENING_BALANCE` transaction SYSTEM → BANK with matching ledger entries, all in one DB transaction.
3. **Flyway:** when wired up, remove the `CREATE DATABASE` and `USE` lines and save as `V1__init_schema.sql`.
4. **Reconciliation (Feature 9):** treat the SYSTEM account as an exception when checking balances against ledger totals.
5. **Next stage:** Feature 1, registration and authentication, starting at the Controller.

## Final checklist

- Five tables: User, Account, Transaction, LedgerEntry, Savings
- Primary keys, foreign keys, unique constraints, CHECK constraints, RESTRICT deletion
- Reversal references, `OPENING_BALANCE`, internal SYSTEM counter-account
- No premature optimisation: no extra indexes, no triggers

**Verdict:** the schema is ready to build on. Constraints alone cannot guarantee financial correctness; transaction processing, ledger balancing, idempotency and concurrency control must be implemented correctly in Java.
