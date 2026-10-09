package com.example.ledger;

import com.example.ledger.service.LedgerService;
import com.example.ledger.service.ReconciliationService;
import com.example.ledger.service.fx.ConfiguredExchangeRateProvider;
import com.example.ledger.exception.LedgerException;
import com.example.ledger.service.Money;
import static com.example.ledger.dto.ApiModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.jayway.jsonpath.JsonPath;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.IntFunction;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "ledger.reconciliation.schedule.enabled=false")
@Import(LedgerIT.TimeConfiguration.class)
class LedgerIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withCommand("postgres", "-c", "statement_timeout=15000");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired LedgerService ledger;
    @Autowired ReconciliationService reconciliation;
    @Autowired JdbcTemplate jdbc;
    @Autowired MutableClock clock;
    @MockitoSpyBean ConfiguredExchangeRateProvider rates;
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void resetLedger() {
        jdbc.execute("TRUNCATE ledger_entries, transactions RESTART IDENTITY CASCADE");
        jdbc.update("UPDATE accounts SET balance = 1000, opening_balance = 1000, created_at = '2026-01-01T00:00:00Z', currency = CASE id WHEN 1 THEN 'USD' ELSE 'SGD' END");
        clock.set("2026-03-15T12:00:00Z");
        reset(rates);
    }

    @Test
    void openApiDocumentsAllEndpointsWithoutChangingTheLedger() throws Exception {
        var response = get("/v3/api-docs");
        assertThat(response.statusCode()).isEqualTo(200);
        var document = JsonPath.parse(response.body());
        assertThat(document.<String>read("$.info.title")).isEqualTo("Internal Ledger API");
        assertThat(document.<Map<String, Object>>read("$.paths")).containsOnlyKeys(
                "/transactions", "/transactions/{id}/reversal", "/accounts/{id}",
                "/accounts/{id}/transactions", "/ledger/integrity", "/reconciliation");
        for (String path : List.of("/transactions", "/transactions/{id}/reversal")) {
            String operation = "$.paths['" + path + "'].post";
            assertThat(document.<List<Boolean>>read(operation + ".parameters[?(@.name == 'Idempotency-Key')].required"))
                    .containsExactly(true);
            assertThat(document.<Map<String, Object>>read(operation + ".responses"))
                    .containsKeys("200", "201", "400", "404", "409", "422", "503");
            assertThat(document.<String>read(operation + ".responses['422'].content['application/json'].schema['$ref']"))
                    .isEqualTo("#/components/schemas/ErrorView");
            assertThat(document.<String>read(operation + ".responses['201'].content['application/json'].schema['$ref']"))
                    .isEqualTo("#/components/schemas/TransactionView");
            assertThat(document.<Map<String, Object>>read(operation + ".responses['200'].headers"))
                    .containsKey("Idempotency-Replayed");
        }
        assertThat(document.<Number>read("$.components.schemas.TransferRequest.properties.sourceAccountId.example").intValue())
                .isEqualTo(1);
        assertThat(document.<Number>read("$.components.schemas.TransferRequest.properties.destinationAccountId.example").intValue())
                .isEqualTo(2);
        assertThat(document.<List<String>>read("$.paths['/reconciliation'].get.parameters[?(@.name == 'month')].example"))
                .containsExactly("2026-09");
        assertThat(count("transactions")).isZero();
        assertBalances("1000", "1000");
    }

    @Test
    void swaggerUiServesItsPageAssetsAndLocalApiConfiguration() throws Exception {
        var redirect = get("/swagger-ui.html");
        assertThat(redirect.statusCode()).isEqualTo(302);
        assertThat(redirect.headers().firstValue("Location")).contains("/swagger-ui/index.html");
        var page = get("/swagger-ui/index.html");
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("swagger-ui-bundle.js", "swagger-initializer.js");
        assertThat(get("/swagger-ui/swagger-ui-bundle.js").statusCode()).isEqualTo(200);
        assertThat(get("/swagger-ui/swagger-ui.css").statusCode()).isEqualTo(200);
        var initializer = get("/swagger-ui/swagger-initializer.js");
        assertThat(initializer.statusCode()).isEqualTo(200);
        assertThat(initializer.body()).contains("/v3/api-docs/swagger-config");
        var config = get("/v3/api-docs/swagger-config");
        assertThat(config.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<String>read(config.body(), "$.url")).isEqualTo("/v3/api-docs");
    }

    @Test
    void transfersAndHistoryExposeBothAmountsAndDeterministicPagination() throws Exception {
        var response = post("/transactions", "first", body(1, 2, "100.00"));
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.headers().firstValue("Idempotency-Replayed")).contains("false");
        assertThat(JsonPath.<String>read(response.body(), "$.status")).isEqualTo("POSTED");
        assertThat(JsonPath.<Number>read(response.body(), "$.destinationAmount").doubleValue()).isEqualTo(125);
        assertBalances("900", "1125");
        ledger.transfer("second", request(2, 1, "25"));
        assertBalances("920", "1100");
        assertThat(count("transactions")).isEqualTo(2);
        assertThat(count("ledger_entries")).isEqualTo(4);
        HistoryPage first = ledger.history(1L, 0, 1);
        HistoryPage second = ledger.history(1L, 1, 1);
        assertThat(first.totalElements()).isEqualTo(2);
        assertThat(first.totalPages()).isEqualTo(2);
        assertThat(first.items().getFirst().entryId()).isLessThan(second.items().getFirst().entryId());
        assertThat(first.items().getFirst().change()).isEqualByComparingTo("-100");
        assertThat(reconciliation.integrity().healthy()).isTrue();
        assertThat(get("/accounts/1").statusCode()).isEqualTo(200);
        assertThat(get("/accounts/1/transactions?page=0&size=1").statusCode()).isEqualTo(200);
    }

    @Test
    void validatesHttpRequestsAndReturnsConsistentErrors() throws Exception {
        for (String amount : new String[]{"0", "-1", "1.001", "100000000000000000"}) {
            assertThat(post("/transactions", "invalid", body(1, 2, amount)).statusCode()).isEqualTo(400);
        }
        assertThat(post("/transactions", null, body(1, 2, "1")).statusCode()).isEqualTo(400);
        assertThat(post("/transactions", "x".repeat(129), body(1, 2, "1")).statusCode()).isEqualTo(400);
        assertThat(post("/transactions", "bad", "{broken}").statusCode()).isEqualTo(400);
        assertThat(post("/transactions", "bad", "{\"sourceAccountId\":1}").statusCode()).isEqualTo(400);
        assertThat(post("/transactions", "bad", body(1, 1, "1")).statusCode()).isEqualTo(400);
        assertThat(post("/transactions", "bad", body(1, 9, "1")).statusCode()).isEqualTo(404);
        assertThat(get("/accounts/9").statusCode()).isEqualTo(404);
        assertThat(get("/accounts/9/transactions").statusCode()).isEqualTo(404);
        assertThat(get("/accounts/1/transactions?size=101").statusCode()).isEqualTo(400);
        assertThat(get("/accounts/1/transactions?page=-1").statusCode()).isEqualTo(400);
        var insufficient = post("/transactions", "bad", body(1, 2, "1001"));
        assertThat(insufficient.statusCode()).isEqualTo(422);
        assertThat(JsonPath.<String>read(insufficient.body(), "$.code")).isEqualTo("INSUFFICIENT_FUNDS");
        assertBalances("1000", "1000");
        assertThat(count("transactions")).isZero();
    }

    @Test
    void preventsClientBackdating() throws Exception {
        String body = "{\"sourceAccountId\":1,\"destinationAccountId\":2,\"amount\":1,\"timestamp\":\"2020-01-01T00:00:00Z\"}";
        assertThat(post("/transactions", "backdated", body).statusCode()).isEqualTo(400);
        assertThat(count("transactions")).isZero();
    }

    @Test
    void idempotencyNormalizesAmountsAndChecksOperationAndPayload() throws Exception {
        var first = post("/transactions", "same", body(1, 2, "10"));
        var replay = post("/transactions", "same", body(1, 2, "10.00"));
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<Number>read(replay.body(), "$.id")).isEqualTo(JsonPath.<Number>read(first.body(), "$.id"));
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(post("/transactions", "same", body(1, 2, "11")).statusCode()).isEqualTo(409);
        assertThat(post("/transactions/1/reversal", "same", "").statusCode()).isEqualTo(409);
        assertThat(count("transactions")).isEqualTo(1);
        assertBalances("990", "1012.50");
        assertThat(post("/transactions", "new-key", body(1, 2, "10")).statusCode()).isEqualTo(201);
    }

    @Test
    void failedRequestsDoNotReserveKeys() {
        assertThatThrownBy(() -> ledger.transfer("retry", request(1, 2, "1001"))).isInstanceOf(LedgerException.class);
        ledger.transfer("fund", request(2, 1, "10"));
        assertThat(ledger.transfer("retry", request(1, 2, "1001")).replay()).isFalse();
        assertBalances("7", "2241.25");
    }

    @Test
    void databaseFailureRollsBackAllWritesAndKey() {
        jdbc.execute("ALTER TABLE ledger_entries ADD CONSTRAINT test_failure CHECK (amount <> -13)");
        try {
            assertThatThrownBy(() -> ledger.transfer("rollback", request(1, 2, "13"))).isInstanceOf(RuntimeException.class);
            assertBalances("1000", "1000");
            assertThat(count("transactions")).isZero();
            assertThat(count("ledger_entries")).isZero();
        } finally {
            jdbc.execute("ALTER TABLE ledger_entries DROP CONSTRAINT test_failure");
        }
        assertThat(ledger.transfer("rollback", request(1, 2, "13")).replay()).isFalse();
    }

    @Test
    void concurrentDuplicatesPostExactlyOnce() throws Exception {
        List<PostingResult> results = parallel(16, i -> ledger.transfer("concurrent-key", request(1, 2, "10")));
        assertThat(results.stream().map(r -> r.transaction().id()).distinct()).hasSize(1);
        assertThat(results.stream().filter(r -> !r.replay())).hasSize(1);
        assertThat(count("transactions")).isEqualTo(1);
        assertThat(count("ledger_entries")).isEqualTo(2);
        assertBalances("990", "1012.50");
    }

    @Test
    void simultaneousSpendingCannotOverdraw() throws Exception {
        List<Boolean> results = parallel(20, i -> {
            try {
                ledger.transfer("spend-" + i, request(1, 2, "100"));
                return true;
            } catch (LedgerException ex) {
                assertThat(ex.getCode()).isEqualTo("INSUFFICIENT_FUNDS");
                return false;
            }
        });
        assertThat(results.stream().filter(Boolean::booleanValue)).hasSize(10);
        assertBalances("0", "2250");
        assertThat(count("transactions")).isEqualTo(10);
        assertThat(reconciliation.integrity().healthy()).isTrue();
    }

    @Test
    void oppositeDirectionTransfersDoNotLoseUpdatesOrDeadlock() throws Exception {
        parallel(40, i -> i % 2 == 0
                ? ledger.transfer("opposite-" + i, request(1, 2, "1"))
                : ledger.transfer("opposite-" + i, request(2, 1, "1.25")));
        assertBalances("1000", "1000");
        assertThat(count("transactions")).isEqualTo(40);
        assertThat(reconciliation.integrity().healthy()).isTrue();
    }

    @Test
    void sameCurrencyTransfersConserveCombinedBalance() {
        jdbc.update("UPDATE accounts SET currency = 'USD' WHERE id = 2");
        ledger.transfer("same-currency", request(1, 2, "123.45"));
        assertBalances("876.55", "1123.45");
        var report = reconciliation.integrity();
        assertThat(report.healthy()).isTrue();
        assertThat(report.totalsByCurrency()).singleElement().satisfies(t -> {
            assertThat(t.currency()).isEqualTo("USD");
            assertThat(t.actual()).isEqualByComparingTo("2000");
        });
    }

    @Test
    void rejectsUnrepresentableConversionAndBalanceOverflow() {
        doReturn(new BigDecimal("0.1")).when(rates).rate("USD", "SGD");
        assertThatThrownBy(() -> ledger.transfer("tiny", request(1, 2, "0.01"))).isInstanceOf(LedgerException.class);
        reset(rates);
        jdbc.update("UPDATE accounts SET balance = ? WHERE id = 2", Money.MAX);
        assertThatThrownBy(() -> ledger.transfer("overflow", request(1, 2, "1"))).isInstanceOf(LedgerException.class);
        assertThat(count("transactions")).isZero();
    }

    @Test
    void reversalUsesOriginalRoundedAmountsAndRateDespiteProviderChange() throws Exception {
        var original = ledger.transfer("original", request(1, 2, "0.06")).transaction();
        assertThat(original.destinationAmount()).isEqualByComparingTo("0.08");
        doThrow(new IllegalStateException("rate provider unavailable")).when(rates).rate(anyString(), anyString());
        var reversed = ledger.reverse("reverse", original.id()).transaction();
        assertThat(reversed.sourceAmount()).isEqualByComparingTo("0.08");
        assertThat(reversed.destinationAmount()).isEqualByComparingTo("0.06");
        assertThat(reversed.exchangeRate().sourceCurrency()).isEqualTo("USD");
        assertThat(reversed.exchangeRate().rate()).isEqualByComparingTo("1.25");
        assertBalances("1000", "1000");
        assertThat(ledger.reverse("reverse", original.id()).replay()).isTrue();
        assertThat(post("/transactions/" + original.id() + "/reversal", "again", "").statusCode()).isEqualTo(409);
        assertThat(post("/transactions/" + reversed.id() + "/reversal", "nested", "").statusCode()).isEqualTo(409);
        assertThat(post("/transactions/999/reversal", "missing", "").statusCode()).isEqualTo(404);
        assertThat(ledger.transfer("original", request(1, 2, "0.06")).transaction().id()).isEqualTo(original.id());
        assertThat(count("transactions")).isEqualTo(2);
        assertThat(reconciliation.integrity().healthy()).isTrue();
    }

    @Test
    void cannotReverseFundsThatHaveBeenSpent() {
        Long id = ledger.transfer("original", request(1, 2, "100")).transaction().id();
        ledger.transfer("spent", request(2, 1, "1125"));
        assertThatThrownBy(() -> ledger.reverse("refund", id)).isInstanceOf(LedgerException.class).hasMessageContaining("insufficient");
        assertBalances("1800", "0");
        assertThat(count("transactions")).isEqualTo(2);
    }

    @Test
    void racingReversalsHaveOnlyOneWinner() throws Exception {
        Long id = ledger.transfer("original", request(1, 2, "100")).transaction().id();
        List<Boolean> results = parallel(8, i -> {
            try { ledger.reverse("reverse-" + i, id); return true; }
            catch (LedgerException ex) { assertThat(ex.getCode()).isEqualTo("ALREADY_REVERSED"); return false; }
        });
        assertThat(results.stream().filter(Boolean::booleanValue)).hasSize(1);
        assertBalances("1000", "1000");
    }

    @Test
    void integrityDetectsStoredBalanceCorruptionWithoutRepairingIt() throws Exception {
        ledger.transfer("normal", request(1, 2, "10"));
        jdbc.update("UPDATE accounts SET balance = balance + 5 WHERE id = 1");
        var report = reconciliation.integrity();
        assertThat(report.healthy()).isFalse();
        assertThat(report.accounts().getFirst().discrepancy()).isEqualByComparingTo("5");
        assertThat(report.totalsByCurrency()).filteredOn(t -> t.currency().equals("USD"))
                .singleElement().satisfies(t -> assertThat(t.discrepancy()).isEqualByComparingTo("5"));
        assertThat(ledger.balance(1L).balance()).isEqualByComparingTo("995");
        assertThat(get("/ledger/integrity").statusCode()).isEqualTo(200);
    }

    @Test
    void integrityDetectsMissingEntriesAndCorruptConversion() {
        ledger.transfer("normal", request(1, 2, "10"));
        jdbc.update("DELETE FROM ledger_entries WHERE account_id = 2");
        assertThat(reconciliation.integrity().issues()).anyMatch(s -> s.contains("Missing or incorrect entries"));
        jdbc.update("UPDATE transactions SET exchange_rate = 9");
        assertThat(reconciliation.integrity().issues()).anyMatch(s -> s.contains("Conversion mismatch"));
    }

    @Test
    void backwardClockPreservesPostingOrderAndClosingBalances() {
        clock.set("2026-02-10T12:00:01.123456789Z");
        var first = ledger.transfer("before-clock-change", request(1, 2, "100")).transaction();
        clock.set("2026-02-10T12:00:00Z");
        var second = ledger.transfer("after-clock-change", request(1, 2, "40")).transaction();

        assertThat(first.timestamp()).isEqualTo(Instant.parse("2026-02-10T12:00:01.123456Z"));
        assertThat(second.timestamp()).isEqualTo(first.timestamp());
        var history = ledger.history(1L, 0, 20).items();
        assertThat(history).extracting(item -> item.transaction().id()).containsExactly(first.id(), second.id());
        assertThat(history).extracting(HistoryItem::balanceAfter)
                .containsExactly(new BigDecimal("900.00"), new BigDecimal("860.00"));
        assertBalances("860", "1175");
        assertThat(reconciliation.integrity().healthy()).isTrue();

        clock.set("2026-03-01T00:00:00Z");
        var report = reconciliation.monthly("2026-02");
        assertThat(report.healthy()).isTrue();
        assertThat(report.accounts().getFirst().recordedClosingBalance()).isEqualByComparingTo("860");
        assertThat(report.accounts()).allSatisfy(a -> assertThat(a.discrepancy()).isZero());
    }

    @Test
    void backwardClockAcrossMonthBoundaryDoesNotReopenThePreviousMonth() {
        clock.set("2026-02-28T23:59:59.999999Z");
        ledger.transfer("february", request(1, 2, "100"));
        clock.set("2026-03-01T00:00:00Z");
        var march = ledger.transfer("march", request(1, 2, "40")).transaction();
        clock.set("2026-02-28T23:59:59Z");
        var afterClockChange = ledger.transfer("still-march", request(1, 2, "10")).transaction();
        assertThat(afterClockChange.timestamp()).isEqualTo(march.timestamp());

        clock.set("2026-04-01T00:00:00Z");
        var february = reconciliation.monthly("2026-02");
        var marchReport = reconciliation.monthly("2026-03");
        assertThat(february.healthy()).isTrue();
        assertThat(february.accounts().getFirst().recordedClosingBalance()).isEqualByComparingTo("900");
        assertThat(marchReport.healthy()).isTrue();
        assertThat(marchReport.accounts().getFirst().openingBalance()).isEqualByComparingTo("900");
        assertThat(marchReport.accounts().getFirst().recordedClosingBalance()).isEqualByComparingTo("850");
        assertThat(reconciliation.integrity().healthy()).isTrue();
    }

    @Test
    void reversalAndReplaysPreserveTimestampsWhenClockMovesBackward() {
        clock.set("2026-02-10T12:00:00Z");
        var original = ledger.transfer("original-clock", request(1, 2, "100")).transaction();
        clock.set("2026-02-10T11:00:00Z");
        var reversal = ledger.reverse("reverse-clock", original.id()).transaction();
        assertThat(reversal.timestamp()).isEqualTo(original.timestamp());
        assertBalances("1000", "1000");

        clock.set("2026-02-11T12:00:00Z");
        ledger.transfer("later", request(1, 2, "10"));
        clock.set("2026-02-09T12:00:00Z");
        var originalReplay = ledger.transfer("original-clock", request(1, 2, "100"));
        var reversalReplay = ledger.reverse("reverse-clock", original.id());
        assertThat(originalReplay.replay()).isTrue();
        assertThat(originalReplay.transaction().timestamp()).isEqualTo(original.timestamp());
        assertThat(reversalReplay.replay()).isTrue();
        assertThat(reversalReplay.transaction().timestamp()).isEqualTo(reversal.timestamp());
        assertThat(count("transactions")).isEqualTo(3);
        assertBalances("990", "1012.50");
        assertThat(reconciliation.integrity().healthy()).isTrue();
        clock.set("2026-03-01T00:00:00Z");
        assertThat(reconciliation.monthly("2026-02").healthy()).isTrue();
    }

    @ParameterizedTest
    @CsvSource({"1,2", "2,1"})
    void firstPostingDoesNotPredateEitherAccount(long source, long destination) {
        jdbc.update("UPDATE accounts SET created_at = '2026-02-10T00:00:00Z' WHERE id = 1");
        jdbc.update("UPDATE accounts SET created_at = '2026-02-11T00:00:00Z' WHERE id = 2");
        clock.set("2026-02-09T12:00:00Z");
        var transaction = ledger.transfer("account-opening", request(source, destination, "10")).transaction();
        assertThat(transaction.timestamp()).isEqualTo(Instant.parse("2026-02-11T00:00:00Z"));
        assertThat(reconciliation.integrity().healthy()).isTrue();
        clock.set("2026-03-01T00:00:00Z");
        assertThat(reconciliation.monthly("2026-02").healthy()).isTrue();
    }

    @Test
    void monthlyUsesUtcBoundariesAndExcludesLaterReversals() {
        clock.set("2026-01-31T23:59:59.999999Z");
        ledger.transfer("before", request(1, 2, "100"));
        clock.set("2026-02-01T00:00:00Z");
        Long february = ledger.transfer("feb", request(1, 2, "40")).transaction().id();
        clock.set("2026-03-01T00:00:00Z");
        ledger.reverse("march-refund", february);
        clock.set("2026-04-01T00:00:00Z");
        var feb = reconciliation.monthly("2026-02");
        assertThat(feb.healthy()).isTrue();
        var account = feb.accounts().getFirst();
        assertThat(account.openingBalance()).isEqualByComparingTo("900");
        assertThat(account.debits()).isEqualByComparingTo("40");
        assertThat(account.expectedClosingBalance()).isEqualByComparingTo("860");
        assertThat(account.recordedClosingBalance()).isEqualByComparingTo("860");
        assertThat(reconciliation.monthly("2026-03").accounts().getFirst().credits()).isEqualByComparingTo("40");
    }

    @Test
    void inactiveMonthCarriesForwardAndReportsUnavailableHistory() throws Exception {
        clock.set("2026-01-20T12:00:00Z");
        ledger.transfer("january", request(1, 2, "10"));
        clock.set("2026-03-01T00:00:00Z");
        var report = reconciliation.monthly("2026-02");
        assertThat(report.healthy()).isTrue();
        assertThat(report.accounts().getFirst().openingBalance()).isEqualByComparingTo("990");
        assertThat(report.accounts().getFirst().recordedClosingBalance()).isEqualByComparingTo("990");
        assertThat(report.accounts().getFirst().credits()).isZero();
        var unavailable = reconciliation.monthly("2025-12");
        assertThat(unavailable.healthy()).isFalse();
        assertThat(unavailable.accounts()).allSatisfy(a -> assertThat(a.historyStatus()).isEqualTo("UNAVAILABLE"));
        assertThat(get("/reconciliation?month=2026-02").statusCode()).isEqualTo(200);
        assertThat(get("/reconciliation?month=2026-03").statusCode()).isEqualTo(400);
        assertThat(get("/reconciliation?month=2026-13").statusCode()).isEqualTo(400);
        assertThat(get("/reconciliation?month=invalid").statusCode()).isEqualTo(400);
    }

    @Test
    void monthlyDetectsCorruptedHistoricalBalancesAndMissingEntries() {
        clock.set("2026-02-10T12:00:00Z");
        ledger.transfer("feb", request(1, 2, "10"));
        jdbc.update("UPDATE ledger_entries SET balance_after = balance_after + 5 WHERE account_id = 1");
        clock.set("2026-03-01T00:00:00Z");
        var report = reconciliation.monthly("2026-02");
        assertThat(report.healthy()).isFalse();
        assertThat(report.accounts().getFirst().discrepancy()).isEqualByComparingTo("5");
        jdbc.update("DELETE FROM ledger_entries WHERE account_id = 2");
        assertThat(reconciliation.monthly("2026-02").issues()).anyMatch(s -> s.contains("Missing or incorrect entries"));
    }

    @Test
    void monthlyChecksEarlierTransactionsThatDetermineOpeningBalance() {
        clock.set("2026-01-10T12:00:00Z");
        ledger.transfer("january", request(1, 2, "10"));
        jdbc.update("DELETE FROM ledger_entries");
        clock.set("2026-03-01T00:00:00Z");
        assertThat(reconciliation.monthly("2026-02").issues()).anyMatch(s -> s.contains("Missing or incorrect entries"));
    }

    @Test
    void monthlyReportsAccountOpenedDuringMonth() {
        jdbc.update("UPDATE accounts SET created_at = '2026-02-10T00:00:00Z'");
        var report = reconciliation.monthly("2026-02");
        assertThat(report.healthy()).isTrue();
        assertThat(report.accounts()).allSatisfy(a -> {
            assertThat(a.historyStatus()).isEqualTo("SINCE_ACCOUNT_OPENING");
            assertThat(a.expectedClosingBalance()).isEqualByComparingTo("1000");
        });
    }

    @Test
    void reportsRemainConsistentWhileTransfersAreRunning() throws Exception {
        parallel(24, i -> {
            if (i % 2 == 0) ledger.transfer("report-race-" + i, request(1, 2, "1"));
            else assertThat(reconciliation.integrity().healthy()).isTrue();
            return true;
        });
        assertThat(reconciliation.integrity().healthy()).isTrue();
    }

    private TransferRequest request(long source, long destination, String amount) {
        return new TransferRequest(source, destination, new BigDecimal(amount));
    }

    private String body(long source, long destination, String amount) {
        return "{\"sourceAccountId\":" + source + ",\"destinationAccountId\":" + destination + ",\"amount\":" + amount + "}";
    }

    private void assertBalances(String source, String destination) {
        assertThat(ledger.balance(1L).balance()).isEqualByComparingTo(source);
        assertThat(ledger.balance(2L).balance()).isEqualByComparingTo(destination);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private HttpResponse<String> post(String path, String key, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) builder.header("Idempotency-Key", key);
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private <T> List<T> parallel(int count, IntFunction<T> operation) throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                final int index = i;
                futures.add(executor.submit(() -> { start.await(); return operation.apply(index); }));
            }
            start.countDown();
            List<T> result = new ArrayList<>();
            for (Future<T> future : futures) result.add(future.get(30, TimeUnit.SECONDS));
            return result;
        }
    }

    @TestConfiguration
    static class TimeConfiguration {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }

    static class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-03-15T12:00:00Z");
        void set(String value) { now = Instant.parse(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
