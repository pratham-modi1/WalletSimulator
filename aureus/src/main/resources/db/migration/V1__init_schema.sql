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