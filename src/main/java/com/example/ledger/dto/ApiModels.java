package com.example.ledger.dto;

import com.example.ledger.model.LedgerTransaction;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class ApiModels {
    private ApiModels() {}

    public record TransferRequest(@NotNull @Positive Long sourceAccountId,
            @NotNull @Positive Long destinationAccountId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 17, fraction = 2) BigDecimal amount) {}

    public record RateView(String sourceCurrency, String destinationCurrency, BigDecimal rate) {}

    public record TransactionView(Long id, String status, Long sourceAccountId, Long destinationAccountId,
            String sourceCurrency, String destinationCurrency, BigDecimal sourceAmount, BigDecimal destinationAmount,
            RateView exchangeRate, Instant timestamp, Long reversalOf) {
        public static TransactionView of(LedgerTransaction t) {
            // A reversal retains the original quote direction, not a newly calculated reciprocal rate.
            RateView rate = new RateView(t.getReversalOf() == null ? t.getSourceCurrency() : t.getDestinationCurrency(),
                    t.getReversalOf() == null ? t.getDestinationCurrency() : t.getSourceCurrency(), t.getExchangeRate());
            return new TransactionView(t.getId(), "POSTED", t.getSourceAccountId(), t.getDestinationAccountId(),
                    t.getSourceCurrency(), t.getDestinationCurrency(), t.getSourceAmount(), t.getDestinationAmount(),
                    rate, t.getCreatedAt(), t.getReversalOf());
        }
    }

    public record PostingResult(TransactionView transaction, boolean replay) {}
    public record AccountView(Long id, String currency, BigDecimal balance) {}
    public record HistoryItem(Long entryId, BigDecimal change, BigDecimal balanceAfter, TransactionView transaction) {}
    public record HistoryPage(List<HistoryItem> items, int page, int size, long totalElements, int totalPages) {}
    public record ErrorView(String code, String message) {}
    public record BalanceCheck(Long accountId, String currency, BigDecimal expected, BigDecimal actual, BigDecimal discrepancy) {}
    public record CurrencyCheck(String currency, BigDecimal expected, BigDecimal actual, BigDecimal discrepancy) {}
    public record IntegrityReport(Instant checkedAt, boolean healthy, List<BalanceCheck> accounts,
            List<CurrencyCheck> totalsByCurrency, List<String> issues) {}
    public record MonthlyAccount(Long accountId, String currency, String historyStatus, BigDecimal openingBalance,
            BigDecimal credits, BigDecimal debits, BigDecimal expectedClosingBalance,
            BigDecimal recordedClosingBalance, BigDecimal discrepancy) {}
    public record MonthlyReport(String month, String timezone, boolean healthy, List<MonthlyAccount> accounts, List<String> issues) {}
}
