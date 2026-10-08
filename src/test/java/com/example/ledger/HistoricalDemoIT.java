package com.example.ledger;

import static org.assertj.core.api.Assertions.*;

import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@ActiveProfiles("demo")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HistoricalDemoIT {
    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("ledger_demo");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @LocalServerPort int port;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void septemberReportExcludesOctoberReversalAndMatchesSeededHistory() throws Exception {
        var report = JsonPath.parse(get("/reconciliation?month=2026-09"));
        assertThat(report.<Boolean>read("$.healthy")).isTrue();
        assertThat(report.<Number>read("$.accounts[0].openingBalance").doubleValue()).isEqualTo(900);
        assertThat(report.<Number>read("$.accounts[0].debits").doubleValue()).isEqualTo(40);
        assertThat(report.<Number>read("$.accounts[0].expectedClosingBalance").doubleValue()).isEqualTo(860);
        assertThat(report.<Number>read("$.accounts[0].recordedClosingBalance").doubleValue()).isEqualTo(860);
        assertThat(report.<Number>read("$.accounts[0].discrepancy").doubleValue()).isZero();
        assertThat(report.<Number>read("$.accounts[1].expectedClosingBalance").doubleValue()).isEqualTo(1175);
        assertThat(JsonPath.<Number>read(get("/accounts/1"), "$.balance").doubleValue()).isEqualTo(900);
        assertThat(JsonPath.<Boolean>read(get("/ledger/integrity"), "$.healthy")).isTrue();
        assertThat(JsonPath.<Number>read(get("/accounts/1/transactions"), "$.totalElements").intValue()).isEqualTo(3);
    }

    @Test
    void monthlyEndpointDetectsAndReportsAnIncorrectDemoCheckpoint() throws Exception {
        try {
            setSeptemberCheckpoint(865);
            var report = JsonPath.parse(get("/reconciliation?month=2026-09"));
            assertThat(report.<Boolean>read("$.healthy")).isFalse();
            assertThat(report.<Number>read("$.accounts[0].expectedClosingBalance").doubleValue()).isEqualTo(860);
            assertThat(report.<Number>read("$.accounts[0].recordedClosingBalance").doubleValue()).isEqualTo(865);
            assertThat(report.<Number>read("$.accounts[0].discrepancy").doubleValue()).isEqualTo(5);
        } finally {
            setSeptemberCheckpoint(860);
        }
        assertThat(JsonPath.<Boolean>read(get("/ledger/integrity"), "$.healthy")).isTrue();
    }

    @Test
    void migrationsDoNotReinsertHistoricalFixtures() {
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions", Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entries", Long.class)).isEqualTo(6);
    }

    @Test
    void fixtureMigrationRefusesARegularDatabase() {
        jdbc.execute("CREATE DATABASE ledger_guard_test");
        Flyway regular = Flyway.configure().dataSource(POSTGRES.getJdbcUrl().replace("/ledger_demo", "/ledger_guard_test"),
                POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration", "classpath:db/demo").load();
        assertThatThrownBy(regular::migrate).hasStackTraceContaining("require the dedicated ledger_demo database");
    }

    @Test
    void fixtureMigrationRefusesToOverwriteExistingBalances() {
        Flyway existing = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("existing_ledger").locations("classpath:db/migration").load();
        existing.migrate();
        jdbc.update("UPDATE existing_ledger.accounts SET balance = 999 WHERE id = 1");
        Flyway withFixtures = Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas("existing_ledger").locations("classpath:db/migration", "classpath:db/demo").load();
        assertThatThrownBy(withFixtures::migrate).hasStackTraceContaining("require pristine accounts");
        assertThat(jdbc.queryForObject("SELECT balance FROM existing_ledger.accounts WHERE id = 1", java.math.BigDecimal.class))
                .isEqualByComparingTo("999");
    }

    private void setSeptemberCheckpoint(int amount) {
        jdbc.update("UPDATE ledger_entries SET balance_after = ? WHERE account_id = 1 AND transaction_id = "
                + "(SELECT id FROM transactions WHERE idempotency_key = 'fixture-september-transfer')", amount);
    }

    private String get(String path) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }
}
