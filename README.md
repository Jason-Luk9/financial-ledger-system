# Internal Ledger

A Java 21 / Spring Boot 4.1.1 API for account transfers, reversals, balance queries, and reconciliation, backed by PostgreSQL 17.

## Getting started

Requires JDK 21 and Docker with Compose. The Maven wrapper downloads Maven. Set `JAVA_HOME` to JDK 21 if your default JDK differs.

```sh
docker compose up -d --wait
./mvnw spring-boot:run
```

The API runs at `http://localhost:8080`. Open [Swagger UI](http://localhost:8080/swagger-ui.html) to try requests, or view the [OpenAPI document](http://localhost:8080/v3/api-docs).

Flyway creates the schema and seeds two accounts:

| ID | Account | Currency | Opening balance |
|---|---|---|---|
| 1 | A | USD | 1,000.00 |
| 2 | B | SGD | 1,000.00 |

Data survives restarts and `docker compose down`. Running `docker compose down --volumes` deletes the development ledger and allows a fresh start.

### Configuration

The default database is `ledger`, with username and password `ledger`. The API and database bind to loopback by default.

| Environment variable | Default |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/ledger` |
| `DB_USERNAME`, `DB_PASSWORD` | `ledger` |
| `USD_TO_SGD` | `1.25` |
| `SGD_TO_USD` | `0.80` |
| `SERVER_PORT` | `8080` |
| `SERVER_ADDRESS` | `127.0.0.1` |
| `RECONCILIATION_SCHEDULE_ENABLED` | `true` |
| `RECONCILIATION_SCHEDULE_CRON` | `0 5 0 1 * *` |

## Assumptions

- There are exactly two accounts, and either can transfer to the other. Account creation is outside scope.
- Opening balances define the initial funds. Deposits, withdrawals, fees, overdrafts, and external payment settlement are outside scope.
- Transfer amounts use the source account's currency. USD/SGD exchange rates are supplied through configuration.
- Reversals return the full original amounts and require sufficient funds in the original recipient's account. Partial reversals and reversing a reversal are unsupported.
- Calendar months and posting timestamps use UTC. Clients cannot backdate transactions.
- This is a local application without authentication.

## API usage

| Method | Endpoint | Purpose |
|---|---|---|
| POST | `/transactions` | Transfer between accounts |
| POST | `/transactions/{id}/reversal` | Reverse a transfer |
| GET | `/accounts/{id}` | Current balance and currency |
| GET | `/accounts/{id}/transactions` | Paginated transaction history |
| GET | `/ledger/integrity` | Check current balances and ledger entries |
| GET | `/reconciliation?month=YYYY-MM` | Reconcile a completed UTC month |

### Transfer and reversal

Transfer USD 100 from account 1 to account 2:

```sh
curl -i http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-transfer-1' \
  -d '{"sourceAccountId":1,"destinationAccountId":2,"amount":100.00}'
```

On a fresh database, this returns `201 Created` with a response such as:

```json
{
  "id": 1,
  "status": "POSTED",
  "sourceAccountId": 1,
  "destinationAccountId": 2,
  "sourceCurrency": "USD",
  "destinationCurrency": "SGD",
  "sourceAmount": 100.00,
  "destinationAmount": 125.00,
  "exchangeRate": {"sourceCurrency": "USD", "destinationCurrency": "SGD", "rate": 1.25},
  "timestamp": "2026-10-08T13:00:00Z",
  "reversalOf": null
}
```

The server generates the ID and timestamp. The balances are now USD 900 and SGD 1,125.

Reusing the same key and request returns `200 OK`, the original result, and `Idempotency-Replayed: true`. It does not move money again. Amounts `100` and `100.00` are equivalent. A different payload or operation with the same key returns `409`; use a new key for an intentional new transfer.

To reverse the transfer, replace `1` with its returned transaction ID:

```sh
curl -i -X POST http://localhost:8080/transactions/1/reversal \
  -H 'Idempotency-Key: demo-reversal-1'
```

This posts a separate transaction linked through `reversalOf`, returning SGD 125 and USD 100. Retrying with the reversal's original key replays its result. A second reversal with a different key returns `409`.

### Balances, history, and reports

```sh
curl -sS http://localhost:8080/accounts/1
curl -sS http://localhost:8080/accounts/2
curl -sS 'http://localhost:8080/accounts/1/transactions?page=0&size=20'
```

History is oldest first, ordered by transaction timestamp and then entry ID. Each item includes the signed `change`, `balanceAfter`, and transaction details. The response contains `items`, `page`, `size`, `totalElements`, and `totalPages`. Pages start at zero; page size defaults to 20 and is limited to 100.

```sh
curl -sS http://localhost:8080/ledger/integrity
```

```sh
curl -sS 'http://localhost:8080/reconciliation?month=2026-09'
```

Choose a completed month during the ledger's lifetime. Months before account creation return `UNAVAILABLE`; current and future months return `400`. The [historical demo](#appendix-historical-reconciliation-demo) provides data for September 2026.

Reports return `200` even when discrepancies exist. Check `healthy`, `issues`, and the account figures. Discrepancy is actual or recorded balance minus expected balance. Reports do not repair data.

### Validation and errors

Amounts must be positive, with at most 17 integer digits and two decimal places. Excess precision such as `1.000` is rejected. Clients should use decimal arithmetic.

Both POST endpoints require an `Idempotency-Key` containing 1 to 128 printable, non-space ASCII characters. Successful keys are retained indefinitely; failed transactions do not reserve their keys. Unknown transfer fields, including client-supplied timestamps, are rejected.

| Status | Meaning |
|---|---|
| `201` | New transfer or reversal posted |
| `200` | Query succeeded or successful request replayed |
| `400` | Invalid input, self-transfer, missing key, or invalid pagination/month |
| `404` | Account or transaction does not exist |
| `409` | Key reused for different input, already reversed, or reversal of a reversal |
| `422` | Insufficient funds, unsupported conversion, zero rounded output, or balance overflow |
| `503` | Database operation failed; retry with the same key |

Errors use `{ "code": "INSUFFICIENT_FUNDS", "message": "Source account has insufficient funds" }`.

## Design considerations

### Persistence

`LedgerController` delegates posting to `LedgerService` and reports to `ReconciliationService`. Spring Data JPA handles persistence. Flyway manages the schema; Hibernate validates it.

| Table | Stored data |
|---|---|
| `accounts` | Currency, opening balance, current balance, creation timestamp |
| `transactions` | Both accounts and currency amounts, original rate, timestamp, idempotency key, request fingerprint, optional reversal reference |
| `ledger_entries` | Two entries per transaction, each with a signed amount and resulting account balance |

Transactions and entries are immutable in Hibernate, and the API has no history-editing or deletion operations. Direct database changes remain possible and are used in discrepancy tests.

### Atomicity and concurrency

Each posting locks both accounts in ascending ID order, checks idempotency and funds, then updates both balances and inserts the transaction and entries in one database transaction. Any failure rolls back every change. Database uniqueness constraints enforce one successful transaction per idempotency key and one reversal per original transaction.

Locking both accounts serializes writes across application instances. This suits the two-account scope, with the tradeoff that transfers cannot execute in parallel.

### Posting timestamps

While holding the account locks, the service chooses the latest of the current clock time, the greatest stored transaction timestamp, and both account creation timestamps. Timestamps use microsecond precision; entry IDs order ties. Reading the stored timestamp inside the transaction preserves this ordering across instances and restarts.

For example, after a USD 100 transfer at `12:00:01`, the clock could move backward before a USD 40 transfer. The second posting also receives `12:00:01`, and entry IDs preserve the order of balances 900 then 860. The service logs adjusted timestamps. A posting timestamp may therefore be ahead of wall-clock time; retries retain the original timestamp, and historical records are not rewritten.

### Currency conversion

Money uses Java `BigDecimal` and PostgreSQL `NUMERIC(19,2)`; rates use `NUMERIC(30,12)`. Destination amounts are rounded once with `HALF_EVEN`. Outputs that round to zero are rejected, and same-currency transfers use rate 1.

`ExchangeRateProvider` currently reads fixed USD/SGD rates from configuration. They can be changed through `USD_TO_SGD` and `SGD_TO_USD`, which keeps setup and testing simple. Each transaction stores its rate, so configuration changes affect new transfers only. Reversals return the original posted amounts and retain the original quote's currency direction.

To use live rates, implement an FX API client behind the same interface and cache quotes briefly. Fetch the quote before locking accounts, save its rate and timestamp with the transaction, and reject new transfers when no fresh quote is available. Retries and reversals should keep using the stored transaction so they still work if the rate service is unavailable.

### Integrity and reconciliation

Expected balance is the opening balance plus the sum of signed entries. Integrity checks compare this against current balances, verify every balance checkpoint, and validate transaction entries, conversions, and reversals.

Totals are reported separately by currency because USD and SGD have different units. Same-currency transfers conserve the combined balance; conversions change the expected totals for each currency. Base-currency valuation, FX clearing accounts, and FX gains or losses are outside scope.

Monthly reports use `[month start, next month start)` in UTC. They reconstruct opening balances and monthly movements, then compare expected closing balances with the last checkpoint before month end. Inactive months carry balances forward. Accounts opened during the month report `SINCE_ACCOUNT_OPENING`. Later reversals belong to their posting month.

Reports use PostgreSQL `REPEATABLE_READ` so all reads see a consistent database snapshot during concurrent transfers. Checks compare internal records and cannot detect coordinated changes to every related record. Saving a separate account-balance snapshot at month-end would provide a stronger comparison against transaction history.

Reports currently load history into memory, which keeps the calculation straightforward. As history grows, calculate totals in SQL and read entries in batches to reduce memory use.

### Automated reconciliation

The application checks the previous completed UTC month on startup and at **00:05 UTC on the first of each month** (**08:05 Singapore time**). Results are logged with the month, trigger, account figures, and issues:

| Level | Result |
|---|---|
| INFO | Healthy report, or a skipped month before all accounts were created |
| WARN | Discrepancies found |
| ERROR | Check failed; startup and future checks continue |

Set `RECONCILIATION_SCHEDULE_ENABLED=false` to disable startup and scheduled checks. `RECONCILIATION_SCHEDULE_CRON` accepts a six-field Spring cron expression in UTC. For a local demonstration, `0 * * * * *` runs every minute.

Startup checks only the previous month. Results are stored in logs, and the manual endpoint recomputes reports. Repeated checks across instances are read-only. Failed checks can be retried through the endpoint, an application restart, or the next scheduled run; there is no immediate automatic retry.

For reliable recovery after downtime, store each month's result and retry failed or missed months on startup. A database lock per month would prevent multiple instances from processing the same month at once.

## Verification

```sh
./mvnw verify
```

Runs unit tests, packages the executable JAR, and runs integration tests against a fresh PostgreSQL 17 Testcontainer. Docker must be running. Tests use a separate database from the Compose ledger. `./mvnw test` runs unit tests without Docker.

Coverage includes atomic rollback, concurrent spending and duplicate requests, reversals, FX rounding, clock changes, corrupted records, UTC month boundaries, and scheduler behavior. Swagger and historical fixtures are also tested.

Run the packaged application after verification:

```sh
java -jar target/ledger-0.0.1-SNAPSHOT.jar
```

## Appendix: historical reconciliation demo

The `demo` profile seeds historical data in a separate database, `ledger_demo` on port `5433`, with the API on port `8081`.

```sh
docker compose -f compose.demo.yaml up --wait
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

Open [the demo Swagger UI](http://localhost:8081/swagger-ui.html) and call `GET /reconciliation` with `month=2026-09`.

| Date, UTC | Event | Account 1, USD | Account 2, SGD |
|---|---|---:|---:|
| August 1, 2026 | Opening balances | 1,000 | 1,000 |
| August 31 | Transfer USD 100 to account 2 | 900 | 1,125 |
| September 15 | Transfer USD 40 to account 2 | 860 | 1,175 |
| October 1 | Reverse the September transfer | 900 | 1,125 |

September's report is healthy: account 1 opens at 900, has debits of 40, and closes at 860; account 2 closes at SGD 1,175. Current balances are USD 900 and SGD 1,125 because the October reversal belongs to the following month.

To demonstrate discrepancy detection, change the September checkpoint to 865 on the demo database only:

```sh
docker compose -f compose.demo.yaml exec db psql -U ledger -d ledger_demo -c "UPDATE ledger_entries SET balance_after = 865 WHERE account_id = 1 AND transaction_id = (SELECT id FROM transactions WHERE idempotency_key = 'fixture-september-transfer');"
```

The September report now returns `healthy: false`, expected closing 860, recorded closing 865, and discrepancy 5. Restore the checkpoint afterward:

```sh
docker compose -f compose.demo.yaml exec db psql -U ledger -d ledger_demo -c "UPDATE ledger_entries SET balance_after = 860 WHERE account_id = 1 AND transaction_id = (SELECT id FROM transactions WHERE idempotency_key = 'fixture-september-transfer');"
```

Fixtures are inserted once. The migration requires the `ledger_demo` database and pristine accounts with no transaction history. Stop the demo API with Ctrl+C and its database with `docker compose -f compose.demo.yaml down`. Adding `--volumes` deletes only the demo volume.

### Targeted tests

Concurrent duplicate requests, overspending, and opposite-direction transfers:

```sh
./mvnw '-Dit.test=LedgerIT#concurrentDuplicatesPostExactlyOnce+simultaneousSpendingCannotOverdraw+oppositeDirectionTransfersDoNotLoseUpdatesOrDeadlock' verify
```

Historical fixtures and discrepancy detection:

```sh
./mvnw -Dit.test=HistoricalDemoIT verify
```
