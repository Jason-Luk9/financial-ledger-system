CREATE TABLE accounts (
    id BIGINT PRIMARY KEY CHECK (id IN (1, 2)),
    currency VARCHAR(3) NOT NULL CHECK (currency IN ('USD', 'SGD')),
    opening_balance NUMERIC(19,2) NOT NULL CHECK (opening_balance >= 0),
    balance NUMERIC(19,2) NOT NULL CHECK (balance >= 0),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE transactions (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_account_id BIGINT NOT NULL REFERENCES accounts(id),
    destination_account_id BIGINT NOT NULL REFERENCES accounts(id),
    source_currency VARCHAR(3) NOT NULL,
    destination_currency VARCHAR(3) NOT NULL,
    source_amount NUMERIC(19,2) NOT NULL CHECK (source_amount > 0),
    destination_amount NUMERIC(19,2) NOT NULL CHECK (destination_amount > 0),
    exchange_rate NUMERIC(30,12) NOT NULL CHECK (exchange_rate > 0),
    created_at TIMESTAMPTZ NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL UNIQUE,
    request_fingerprint VARCHAR(200) NOT NULL,
    reversal_of BIGINT UNIQUE REFERENCES transactions(id),
    CHECK (source_account_id <> destination_account_id),
    CHECK (reversal_of IS NULL OR reversal_of <> id)
);

CREATE TABLE ledger_entries (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id BIGINT NOT NULL REFERENCES transactions(id),
    account_id BIGINT NOT NULL REFERENCES accounts(id),
    amount NUMERIC(19,2) NOT NULL CHECK (amount <> 0),
    balance_after NUMERIC(19,2) NOT NULL CHECK (balance_after >= 0),
    UNIQUE (transaction_id, account_id)
);
CREATE INDEX ledger_entries_account_history ON ledger_entries(account_id, id);
CREATE INDEX transactions_chronology ON transactions(created_at, id);

INSERT INTO accounts (id, currency, opening_balance, balance, created_at)
VALUES (1, 'USD', 1000.00, 1000.00, CURRENT_TIMESTAMP),
       (2, 'SGD', 1000.00, 1000.00, CURRENT_TIMESTAMP);
