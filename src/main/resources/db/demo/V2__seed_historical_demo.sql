-- Deliberately dated fixtures for the opt-in demo profile only.
-- These are inputs to the real reconciliation implementation, not mocked reports.
DO $$
DECLARE
    august_transfer BIGINT;
    september_transfer BIGINT;
    october_reversal BIGINT;
BEGIN
    IF current_database() <> 'ledger_demo' THEN
        RAISE EXCEPTION 'Historical fixtures require the dedicated ledger_demo database';
    END IF;
    IF EXISTS (SELECT 1 FROM transactions) OR EXISTS (SELECT 1 FROM ledger_entries)
            OR (SELECT COUNT(*) FROM accounts) <> 2
            OR EXISTS (SELECT 1 FROM accounts WHERE balance <> 1000 OR opening_balance <> 1000
                OR currency <> CASE id WHEN 1 THEN 'USD' ELSE 'SGD' END) THEN
        RAISE EXCEPTION 'Historical fixtures require pristine accounts and no transaction history';
    END IF;

    UPDATE accounts SET created_at = TIMESTAMPTZ '2026-08-01 00:00:00+00';

    INSERT INTO transactions (source_account_id, destination_account_id, source_currency,
            destination_currency, source_amount, destination_amount, exchange_rate, created_at,
            idempotency_key, request_fingerprint)
    VALUES (1, 2, 'USD', 'SGD', 100, 125, 1.25, TIMESTAMPTZ '2026-08-31 12:00:00+00',
            'fixture-august-transfer', 'TRANSFER:1:2:100.00')
    RETURNING id INTO august_transfer;
    INSERT INTO ledger_entries (transaction_id, account_id, amount, balance_after)
    VALUES (august_transfer, 1, -100, 900), (august_transfer, 2, 125, 1125);

    INSERT INTO transactions (source_account_id, destination_account_id, source_currency,
            destination_currency, source_amount, destination_amount, exchange_rate, created_at,
            idempotency_key, request_fingerprint)
    VALUES (1, 2, 'USD', 'SGD', 40, 50, 1.25, TIMESTAMPTZ '2026-09-15 12:00:00+00',
            'fixture-september-transfer', 'TRANSFER:1:2:40.00')
    RETURNING id INTO september_transfer;
    INSERT INTO ledger_entries (transaction_id, account_id, amount, balance_after)
    VALUES (september_transfer, 1, -40, 860), (september_transfer, 2, 50, 1175);

    INSERT INTO transactions (source_account_id, destination_account_id, source_currency,
            destination_currency, source_amount, destination_amount, exchange_rate, created_at,
            idempotency_key, request_fingerprint, reversal_of)
    VALUES (2, 1, 'SGD', 'USD', 50, 40, 1.25, TIMESTAMPTZ '2026-10-01 00:00:00+00',
            'fixture-october-reversal', 'REVERSAL:' || september_transfer, september_transfer)
    RETURNING id INTO october_reversal;
    INSERT INTO ledger_entries (transaction_id, account_id, amount, balance_after)
    VALUES (october_reversal, 2, -50, 1125), (october_reversal, 1, 40, 900);

    UPDATE accounts SET balance = CASE id WHEN 1 THEN 900 ELSE 1125 END;
END $$;
