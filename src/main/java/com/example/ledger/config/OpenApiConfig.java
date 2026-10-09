package com.example.ledger.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.tags.Tag;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI ledgerOpenApi() {
        return new OpenAPI()
                .info(new Info().title("Internal Ledger API").version("1.0")
                        .description("""
                                Transfer money between account 1 (USD) and account 2 (SGD).
                                Each starts with 1,000.00 on a fresh database. Exchange rates are configured sample rates.

                                Start with a balance lookup, then use **Try it out** on a transfer.
                                Amounts are in the source currency. POST requests change the running ledger.
                                Reuse the same Idempotency-Key to retry a request; use a new key for a new operation.
                                A replay returns the original transaction with HTTP 200; a new posting returns HTTP 201.

                                Reversals return the exact original amounts. Reconciliation reports cover completed
                                UTC months; months before account creation report unavailable history.
                                Automatic checks run on startup and monthly by default, with results in the application logs.
                                """))
                .addTagsItem(new Tag().name("Accounts").description("Current balances and chronological transaction history"))
                .addTagsItem(new Tag().name("Transactions").description("Atomic transfers and full reversals"))
                .addTagsItem(new Tag().name("Reconciliation").description("Read-only integrity checks and month-end reports"));
    }
}
