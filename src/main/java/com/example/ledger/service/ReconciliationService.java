package com.example.ledger.service;

import com.example.ledger.exception.LedgerException;
import com.example.ledger.model.Account;
import com.example.ledger.model.LedgerEntry;
import com.example.ledger.model.LedgerTransaction;
import com.example.ledger.repository.AccountRepository;
import com.example.ledger.repository.EntryRepository;
import com.example.ledger.repository.TransactionRepository;
import static com.example.ledger.dto.ApiModels.*;

import java.math.BigDecimal;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class ReconciliationService {
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final EntryRepository entries;
    private final Clock clock;

    ReconciliationService(AccountRepository accounts, TransactionRepository transactions, EntryRepository entries, Clock clock) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.entries = entries;
        this.clock = clock;
    }

    public IntegrityReport integrity() {
        List<Account> allAccounts = accounts.findAll().stream().sorted(Comparator.comparing(a -> a.getId())).toList();
        List<LedgerEntry> allEntries = entries.chronologicalEntries();
        Map<Long, LedgerTransaction> allTransactions = transactionMap();
        List<String> issues = validateTransactions(allTransactions, allEntries);
        List<BalanceCheck> checks = new ArrayList<>();
        Map<String, BigDecimal[]> totals = new TreeMap<>();
        for (Account account : allAccounts) {
            BigDecimal expected = account.getOpeningBalance();
            for (LedgerEntry entry : allEntries) {
                if (!entry.getAccountId().equals(account.getId())) continue;
                expected = expected.add(entry.getAmount());
                if (expected.compareTo(entry.getBalanceAfter()) != 0) issues.add("Balance-after mismatch at entry " + entry.getId());
                LedgerTransaction t = allTransactions.get(entry.getTransactionId());
                String currency = account.getId().equals(t.getSourceAccountId()) ? t.getSourceCurrency() : t.getDestinationCurrency();
                if (!account.getCurrency().equals(currency)) issues.add("Currency mismatch at entry " + entry.getId());
            }
            BigDecimal discrepancy = account.getBalance().subtract(expected);
            if (discrepancy.signum() != 0) issues.add("Current balance mismatch for account " + account.getId());
            checks.add(new BalanceCheck(account.getId(), account.getCurrency(), expected, account.getBalance(), discrepancy));
            BigDecimal[] total = totals.computeIfAbsent(account.getCurrency(), ignored -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            total[0] = total[0].add(expected);
            total[1] = total[1].add(account.getBalance());
        }
        List<CurrencyCheck> currencyChecks = totals.entrySet().stream().map(e -> new CurrencyCheck(e.getKey(),
                e.getValue()[0], e.getValue()[1], e.getValue()[1].subtract(e.getValue()[0]))).toList();
        return new IntegrityReport(clock.instant(), issues.isEmpty(), checks, currencyChecks, issues);
    }

    public MonthlyReport monthly(String value) {
        YearMonth month;
        try {
            if (value == null || !value.matches("\\d{4}-\\d{2}")) throw new DateTimeParseException("Invalid month", "", 0);
            month = YearMonth.parse(value);
        } catch (DateTimeParseException ex) {
            throw LedgerException.badRequest("INVALID_MONTH", "month must have format YYYY-MM");
        }
        if (!month.isBefore(YearMonth.now(clock.withZone(ZoneOffset.UTC)))) {
            throw LedgerException.badRequest("INCOMPLETE_MONTH", "Only completed UTC calendar months can be reconciled");
        }
        Instant start = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Map<Long, LedgerTransaction> allTransactions = transactionMap();
        // Earlier transactions determine this month's opening balance and must also be checked.
        Map<Long, LedgerTransaction> closingTransactions = allTransactions.values().stream()
                .filter(t -> t.getCreatedAt().isBefore(end))
                .collect(Collectors.toMap(t -> t.getId(), Function.identity()));
        List<LedgerEntry> allEntries = entries.chronologicalEntries();
        List<String> issues = validateTransactions(allTransactions, allEntries, closingTransactions.keySet());
        List<MonthlyAccount> results = new ArrayList<>();
        for (Account account : accounts.findAll().stream().sorted(Comparator.comparing(a -> a.getId())).toList()) {
            if (!account.getCreatedAt().isBefore(end)) {
                results.add(new MonthlyAccount(account.getId(), account.getCurrency(), "UNAVAILABLE", null, null, null, null, null, null));
                issues.add("Account " + account.getId() + " did not exist during this month");
                continue;
            }
            BigDecimal opening = account.getOpeningBalance();
            BigDecimal running = account.getOpeningBalance();
            BigDecimal credits = BigDecimal.ZERO.setScale(2);
            BigDecimal debits = BigDecimal.ZERO.setScale(2);
            BigDecimal recorded = account.getOpeningBalance();
            for (LedgerEntry entry : allEntries) {
                if (!entry.getAccountId().equals(account.getId())) continue;
                Instant postedAt = allTransactions.get(entry.getTransactionId()).getCreatedAt();
                if (!postedAt.isBefore(end)) continue;
                running = running.add(entry.getAmount());
                recorded = entry.getBalanceAfter();
                if (running.compareTo(recorded) != 0) issues.add("Balance-after mismatch at entry " + entry.getId());
                if (postedAt.isBefore(start)) {
                    opening = opening.add(entry.getAmount());
                } else if (entry.getAmount().signum() > 0) {
                    credits = credits.add(entry.getAmount());
                } else {
                    debits = debits.subtract(entry.getAmount());
                }
            }
            BigDecimal closing = opening.add(credits).subtract(debits);
            BigDecimal discrepancy = recorded.subtract(closing);
            if (discrepancy.signum() != 0) issues.add("Closing balance mismatch for account " + account.getId());
            String status = account.getCreatedAt().isAfter(start) ? "SINCE_ACCOUNT_OPENING" : "AVAILABLE";
            results.add(new MonthlyAccount(account.getId(), account.getCurrency(), status, opening, credits, debits, closing, recorded, discrepancy));
        }
        return new MonthlyReport(value, "UTC", issues.isEmpty(), results, issues);
    }

    private Map<Long, LedgerTransaction> transactionMap() {
        return transactions.findAll().stream().collect(Collectors.toMap(t -> t.getId(), Function.identity()));
    }

    private List<String> validateTransactions(Map<Long, LedgerTransaction> all, List<LedgerEntry> allEntries) {
        return validateTransactions(all, allEntries, all.keySet());
    }

    private List<String> validateTransactions(Map<Long, LedgerTransaction> all, List<LedgerEntry> allEntries, Set<Long> selected) {
        List<String> issues = new ArrayList<>();
        Map<Long, List<LedgerEntry>> grouped = allEntries.stream().collect(Collectors.groupingBy(e -> e.getTransactionId()));
        for (Long id : selected.stream().sorted().toList()) {
            LedgerTransaction t = all.get(id);
            List<LedgerEntry> postings = grouped.getOrDefault(id, List.of());
            boolean correctEntries = postings.size() == 2
                    && postings.stream().anyMatch(e -> e.getAccountId().equals(t.getSourceAccountId()) && e.getAmount().compareTo(t.getSourceAmount().negate()) == 0)
                    && postings.stream().anyMatch(e -> e.getAccountId().equals(t.getDestinationAccountId()) && e.getAmount().compareTo(t.getDestinationAmount()) == 0);
            if (!correctEntries) issues.add("Missing or incorrect entries for transaction " + id);
            if (t.getReversalOf() == null) {
                BigDecimal converted = t.getSourceAmount().multiply(t.getExchangeRate()).setScale(2, java.math.RoundingMode.HALF_EVEN);
                if (converted.compareTo(t.getDestinationAmount()) != 0) issues.add("Conversion mismatch for transaction " + id);
            } else {
                LedgerTransaction original = all.get(t.getReversalOf());
                if (original == null || original.getReversalOf() != null
                        || !t.getSourceAccountId().equals(original.getDestinationAccountId())
                        || !t.getDestinationAccountId().equals(original.getSourceAccountId())
                        || !t.getSourceCurrency().equals(original.getDestinationCurrency())
                        || !t.getDestinationCurrency().equals(original.getSourceCurrency())
                        || t.getSourceAmount().compareTo(original.getDestinationAmount()) != 0
                        || t.getDestinationAmount().compareTo(original.getSourceAmount()) != 0
                        || t.getExchangeRate().compareTo(original.getExchangeRate()) != 0) {
                    issues.add("Reversal mismatch for transaction " + id);
                }
            }
            if (t.getSourceCurrency().equals(t.getDestinationCurrency())
                    && (t.getSourceAmount().compareTo(t.getDestinationAmount()) != 0 || t.getExchangeRate().compareTo(BigDecimal.ONE) != 0)) {
                issues.add("Same-currency conservation failure for transaction " + id);
            }
        }
        return issues;
    }
}
