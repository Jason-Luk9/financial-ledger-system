# Internal Ledger

A Java 21 / Spring Boot 4.1.1 API for transfers between two accounts, with PostgreSQL 17 persistence, idempotent posting, exact reversals, and reconciliation.

## Run

Requirements: JDK 21 and Docker with Compose. Maven is downloaded by the included wrapper. Set `JAVA_HOME` to your JDK 21 installation if your system Maven or default JDK uses another version.

```sh
docker compose up -d --wait
./mvnw spring-boot:run
```

The API listens on `http://localhost:8080`. Flyway creates the schema and seeds these accounts once:

| ID | Account | Currency | Opening balance |
|---|---|---|---|
| 1 | A | USD | 1,000.00 |
| 2 | B | SGD | 1,000.00 |

PostgreSQL uses a named Docker volume. Stopping or restarting the app or running `docker compose down` preserves data. `docker compose down --volumes` **deletes the development ledger** and allows a fresh start.

Default database credentials are `ledger` / `ledger`, database `ledger`. Both the database port and API bind to loopback by default. This is an unauthenticated local assignment demo, not a public banking API.

| Environment variable | Default |
|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/ledger` |
| `DB_USERNAME`, `DB_PASSWORD` | `ledger` |
| `USD_TO_SGD` | `1.25` |
| `SGD_TO_USD` | `0.80` |
| `SERVER_PORT` | `8080` |
| `SERVER_ADDRESS` | `127.0.0.1` |

## API walkthrough

### Historical reconciliation demo

An optional `demo` profile seeds historical inputs so you can exercise the real reconciliation endpoint for **September 2026**.
It runs beside the normal application using a separate PostgreSQL database (`ledger_demo` on port `5433`) and API port (`8081`).
It does not change your normal ledger on ports `5432` / `8080`.

In a separate terminal, with Java 21 selected:

```sh
docker compose -f compose.demo.yaml up --wait
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

Open [the demo Swagger UI](http://localhost:8081/swagger-ui.html) and execute `GET /reconciliation` with `month=2026-09`.
The fixture history is:

| Date, UTC | Event | Account 1, USD | Account 2, SGD |
|---|---|---:|---:|
| August 1, 2026 | Opening balances | 1,000 | 1,000 |
| August 31 | Transfer USD 100 to account 2 | 900 | 1,125 |
| September 15 | Transfer USD 40 to account 2 | 860 | 1,175 |
| October 1 | Reverse the September transfer | 900 | 1,125 |

September's report should show `healthy: true`, account 1 opening at `900`, debits of `40`, and expected/recorded closing balances of `860` with discrepancy `0`.
Account 2 closes at SGD `1175`. The current balances are USD `900` / SGD `1125`: the October reversal changes the current balance but not September's closing balance.
The normal integrity and history endpoints also work on this dataset. The API still prohibits client backdating; only this opt-in fixture migration creates historical records.

To demonstrate detection of an error, **on this demo database only**, change the September checkpoint from 860 to 865:

```sh
docker compose -f compose.demo.yaml exec db psql -U ledger -d ledger_demo -c "UPDATE ledger_entries SET balance_after = 865 WHERE account_id = 1 AND transaction_id = (SELECT id FROM transactions WHERE idempotency_key = 'fixture-september-transfer');"
```

Run the September report again. It should return `healthy: false`, expected closing `860`, recorded closing `865`, and discrepancy `5` for account 1. Restore the fixture afterward:

```sh
docker compose -f compose.demo.yaml exec db psql -U ledger -d ledger_demo -c "UPDATE ledger_entries SET balance_after = 860 WHERE account_id = 1 AND transaction_id = (SELECT id FROM transactions WHERE idempotency_key = 'fixture-september-transfer');"
```

Fixtures are inserted once and survive restarts. The migration refuses to seed a database other than `ledger_demo`, or overwrite non-pristine accounts/history.
Stop the demo API with Ctrl+C and its database with `docker compose -f compose.demo.yaml down`. Adding `--volumes` to that **demo** command deletes only its demo volume for a fresh run.

### Swagger UI

After starting the application, open **[Swagger UI](http://localhost:8080/swagger-ui.html)** to browse and execute requests.
The generated OpenAPI document is at [http://localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs).

1. Expand **Accounts → Get account balance**, select **Try it out**, enter `1`, and select **Execute**.
2. Under **Transactions → Transfer money**, use the example body and an `Idempotency-Key` such as `swagger-transfer-1`.
3. Execute again with the same key/body to see an idempotent replay. Use a new key for another intentional transfer.
4. To reverse it, enter the returned transaction ID under **Reverse a transfer**, with a separate key such as `swagger-reversal-1`.

POST operations in Swagger UI change the running ledger. The UI includes request examples, required headers, response schemas, and error descriptions.
The Swagger integration uses [springdoc-openapi](https://springdoc.org/). The terminal examples below remain available.

### Terminal examples

Read the starting balance:

```sh
curl -sS http://localhost:8080/accounts/1
```

Transfer USD 100 from account 1 to account 2:

```sh
curl -i http://localhost:8080/transactions \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-transfer-1' \
  -d '{"sourceAccountId":1,"destinationAccountId":2,"amount":100.00}'
```

On a fresh database, the response is `201 Created` with a body like:

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

The ID and timestamp are generated by the server. Account 1 now holds USD 900; account 2 holds SGD 1,125.

Repeat the exact request to simulate a network retry. It returns `200 OK`, the original result, and `Idempotency-Replayed: true`, without moving money again. `100` and `100.00` represent the same request. A different amount or operation with the same key returns `409`. To intentionally make another transfer, use a new key.

View balances and history:

```sh
curl -sS http://localhost:8080/accounts/1
curl -sS http://localhost:8080/accounts/2
curl -sS 'http://localhost:8080/accounts/1/transactions?page=0&size=20'
```

History is oldest first, ordered by transaction timestamp then entry ID. The response contains `items`, `page`, `size`, `totalElements`, and `totalPages`. Each item includes its signed `change`, `balanceAfter`, and full transaction details. Page numbers start at zero; size defaults to 20 and is limited to 100.

Reverse the transfer, using the ID returned above (replace `1` if necessary):

```sh
curl -i -X POST http://localhost:8080/transactions/1/reversal \
  -H 'Idempotency-Key: demo-reversal-1'
```

This posts a separate transaction returning exactly SGD 125 and USD 100. It preserves the original history, links through `reversalOf`, and restores the starting balances in this walkthrough. Repeating the reversal with its original key replays the result; a different key attempting a second reversal returns `409`.

Run the current integrity check:

```sh
curl -sS http://localhost:8080/ledger/integrity
```

Reconcile a completed UTC calendar month:

```sh
curl -sS 'http://localhost:8080/reconciliation?month=2026-09'
```

Choose a completed month from your ledger's lifetime. A newly initialized normal ledger has no historical transactions: earlier months explicitly return `UNAVAILABLE` rather than a false success. Current/future months return `400`. Use the historical demo above or the controlled integration tests to demonstrate month-end behavior without waiting for a calendar month to pass.

An integrity or reconciliation request returns `200` even when it detects discrepancies; inspect `healthy`, `issues`, and the account figures. Reports never repair data. Discrepancy means **actual/recorded minus expected**.

### Validation and errors

Amounts are denominated in the source account's currency and must be positive, with at most 17 integer digits and two decimal places. Explicit excess precision such as `1.000` is rejected. Use decimal values without binary floating-point calculations in clients.

`Idempotency-Key` is required for both POST endpoints and must contain 1–128 printable, non-space ASCII characters. Successful keys are retained indefinitely. Failed transactions do not reserve their keys. The server rejects unknown transfer fields, including client-supplied timestamps.

| Status | Meaning |
|---|---|
| `201` | New transfer or reversal posted |
| `200` | Query succeeded or successful request replayed |
| `400` | Invalid amount, self-transfer, malformed input, missing key, invalid pagination/month |
| `404` | Account or transaction does not exist |
| `409` | Key reused for different input, already reversed, or reversal of a reversal |
| `422` | Insufficient funds, unsupported conversion, zero rounded output, or balance overflow |
| `503` | Database operation failed; retry with the same key |

Errors use `{ "code": "INSUFFICIENT_FUNDS", "message": "Source account has insufficient funds" }`.

## Design and correctness

```text
src/main/java/com/example/ledger/
├── LedgerApplication.java
├── config/          # Exchange-rate configuration and OpenAPI metadata
├── controller/      # REST endpoints and HTTP error mapping
├── dto/             # Request, response, and report records
├── exception/       # Explicit business failures
├── model/           # JPA entities with private state
├── repository/      # Persistence queries and account locking
└── service/         # Posting, money rules, and reconciliation
    └── fx/          # Exchange-rate interface and configured provider
src/main/resources/db/migration/  # Versioned Flyway schema and seed data
src/test/java/com/example/ledger/ # PostgreSQL integration tests
    └── service/                 # Money and exchange-rate unit tests
```

`LedgerController` handles HTTP and delegates to `LedgerService` for posting and `ReconciliationService` for reports. Spring Data JPA repositories provide persistence. Flyway owns schema changes; Hibernate validates the schema instead of modifying it.

### Stored data

- `accounts`: currency, immutable opening balance, current balance, creation timestamp.
- `transactions`: immutable transfer facts, both currency amounts, original quoted rate, idempotency key/request fingerprint, and an optional unique reversal reference.
- `ledger_entries`: two immutable entries per posted transaction, recording each account's signed change and resulting balance.

Opening balances are the explicitly declared baseline, not external deposits. The database restricts accounts to IDs 1 and 2. The application has no history-editing or deletion API; Hibernate treats transactions and entries as immutable. Direct database administrators can still alter data, which the corruption tests deliberately do.

### Posting and concurrency

Each transfer or reversal runs inside one Spring database transaction:

1. Lock both account rows in ascending ID order using pessimistic write locks.
2. Check the idempotency key and normalized operation/payload after acquiring the locks.
3. Validate funds, conversion, and balance limits.
4. Change both balances and insert the transaction plus both entries.
5. Commit everything together; any failure rolls everything back.

Locking both accounts serializes all writes in this two-account system and gives transfers and reversals the same lock order. Database unique constraints additionally enforce one successful transaction per key and one reversal per original. Coordination happens in PostgreSQL, so it also applies across application instances. There is no in-memory lock or asynchronous balance update.

### Currency conversion and reversals

Money uses Java `BigDecimal` and PostgreSQL `NUMERIC(19,2)`. Rates use `NUMERIC(30,12)`. Destination amounts are rounded once with `HALF_EVEN`; outputs that round to zero are rejected. Same-currency transfers use rate 1.

`ExchangeRateProvider` separates rate lookup from posting. The supplied configuration-backed provider uses demonstration rates, not live market data. Changing configured rates affects future transfers only. Reversals return the exact original posted amounts and do not call the provider. A reversal's `exchangeRate` retains the **original quote's currency direction**, which may differ from the reversal's transfer direction.

Only full reversals are supported. They fail if the original recipient has spent the funds. Partial reversals, reversing reversals, fees, overdrafts, deposits, withdrawals, and external payment settlement are outside scope.

To add live rates later, implement an HTTP-backed provider, obtain credentials from external configuration, and define supported pairs, quote timestamps/expiry, timeouts, caching, and unavailable-rate responses. Fetch quotes before acquiring account locks so network latency does not hold database locks; then validate quote freshness and post using the saved quote. Replays must still return the original result even if the provider is unavailable. Never silently substitute a sample rate for a failed live quote.

### Integrity and reconciliation

The assignment leaves “total money” across currencies undefined. USD and SGD are different units, so this implementation reports totals **separately by currency**. It does not claim a globally constant USD+SGD total or model FX clearing accounts, market valuation, or FX gains/losses.

Expected balance = opening balance + sum of signed ledger entries. The integrity report compares this with current balances, validates every balance-after checkpoint, checks each transaction has its two correct entries, checks original conversions, and checks reversals exactly negate originals. Same-currency transactions must preserve the sum of balances. Per-currency expected totals change when currency is converted.

Monthly reports use the half-open interval `[month start, next month start)` in UTC. They reconstruct opening balances and monthly credits/debits, then compare expected closing balances with the last recorded balance-after checkpoint before month end. Inactive months carry forward prior balances. Earlier transactions are validated too, since they determine the opening balance. Accounts opened during the month use `SINCE_ACCOUNT_OPENING`; earlier unavailable months use `UNAVAILABLE`. A later reversal belongs to its posting month and leaves earlier reports unchanged.

Reports run with PostgreSQL `REPEATABLE_READ` so concurrent transfers cannot mix old and new data in one report. They compare journal-derived balances with internal checkpoints, not independent bank statements. Coordinated tampering with all related records is outside this demo's threat model. Report generation loads history into memory; SQL aggregation/streaming and independent closing snapshots would be natural extensions for a larger ledger.

Scheduling is intentionally omitted: the month-end endpoint can be invoked manually or later called by a scheduled job. There are no external notifications or automatic repair actions.

## Verification

```sh
./mvnw verify
```

This runs unit tests, packages the executable JAR, and runs integration tests against a fresh PostgreSQL 17 Testcontainer. Docker must be running. Tests never use the Compose development database. `./mvnw test` runs only the unit tests and does not require Docker.

| Requirement | Verification |
|---|---|
| Atomic transfers and rollback | Both balances/two entries verified; injected database constraint failure leaves no transaction, entry, balance update, or reserved key |
| Balance and history | HTTP queries, both currency amounts, pagination and timestamp ties |
| Validation and insufficient funds | Invalid bodies/amounts/keys/accounts, self-transfers, overspending, amount and balance limits |
| Duplicate prevention | Sequential retries, normalized amounts, conflicting payloads, concurrent duplicate requests |
| Safe concurrency | Concurrent spending, opposite-direction transfers, and reports during transfers |
| Exchange rates | Both directions, same-currency conservation, half-even boundaries, and zero/overflow rejection |
| Reversals | Exact rounded refunds despite unavailable rate provider, spent funds, retry, duplicate and concurrent reversals |
| Ledger integrity | Deliberately corrupted balances, missing entries, and incorrect stored conversion rates |
| Month-end reconciliation | UTC boundaries, inactive months, unavailable/partial history, earlier corruption, and later reversals |
| Swagger UI and OpenAPI | UI assets, local API configuration, all endpoint schemas, examples, required keys, and response codes |
| Historical demo | September HTTP report, October reversal cutoff, deliberate checkpoint discrepancy, one-time seeding, and refusal to overwrite normal/existing data |

The concurrency tests issue overlapping service calls on eight worker threads against real PostgreSQL: 16 retries of one key post once; 20 attempts to spend USD 100 from USD 1,000 produce exactly 10 successes; and 40 transfers in opposite directions preserve the expected balances. Run just these scenarios with:

```sh
./mvnw '-Dit.test=LedgerIT#concurrentDuplicatesPostExactlyOnce+simultaneousSpendingCannotOverdraw+oppositeDirectionTransfersDoNotLoseUpdatesOrDeadlock' verify
```

To verify the demo fixtures and discrepancy detection in isolation:

```sh
./mvnw -Dit.test=HistoricalDemoIT verify
```

For a packaged run after verification:

```sh
java -jar target/ledger-0.0.1-SNAPSHOT.jar
```

The assignment permits AI assistance. AI was used to help scaffold and implement the API, tests, and documentation; the explicit assumptions and runnable tests are included so the design can be reviewed and discussed.
