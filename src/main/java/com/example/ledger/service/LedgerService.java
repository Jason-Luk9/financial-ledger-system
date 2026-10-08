package com.example.ledger.service;

import com.example.ledger.exception.LedgerException;
import com.example.ledger.model.Account;
import com.example.ledger.model.LedgerEntry;
import com.example.ledger.model.LedgerTransaction;
import com.example.ledger.repository.AccountRepository;
import com.example.ledger.repository.EntryRepository;
import com.example.ledger.repository.TransactionRepository;
import com.example.ledger.service.fx.ExchangeRateProvider;
import static com.example.ledger.dto.ApiModels.*;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {
    private final AccountRepository accounts;
    private final TransactionRepository transactions;
    private final EntryRepository entries;
    private final ExchangeRateProvider rates;
    private final Clock clock;

    LedgerService(AccountRepository accounts, TransactionRepository transactions, EntryRepository entries,
            ExchangeRateProvider rates, Clock clock) {
        this.accounts = accounts;
        this.transactions = transactions;
        this.entries = entries;
        this.rates = rates;
        this.clock = clock;
    }

    @Transactional
    public PostingResult transfer(String key, TransferRequest request) {
        validateKey(key);
        BigDecimal amount = Money.amount(request.amount());
        if (request.sourceAccountId() == null || request.destinationAccountId() == null
                || request.sourceAccountId() <= 0 || request.destinationAccountId() <= 0) {
            throw LedgerException.badRequest("INVALID_ACCOUNT", "Account IDs must be positive");
        }
        if (request.sourceAccountId().equals(request.destinationAccountId())) {
            throw LedgerException.badRequest("SELF_TRANSFER", "Source and destination must differ");
        }
        String fingerprint = "TRANSFER:" + request.sourceAccountId() + ":" + request.destinationAccountId() + ":" + amount.toPlainString();
        List<Account> locked = accounts.lockAccounts();
        PostingResult replay = replay(key, fingerprint);
        if (replay != null) return replay;
        Account source = account(locked, request.sourceAccountId());
        Account destination = account(locked, request.destinationAccountId());
        BigDecimal rate = rates.rate(source.getCurrency(), destination.getCurrency());
        return post(key, fingerprint, source, destination, amount, Money.convert(amount, rate), rate, null);
    }

    @Transactional
    public PostingResult reverse(String key, Long originalId) {
        validateKey(key);
        List<Account> locked = accounts.lockAccounts();
        String fingerprint = "REVERSAL:" + originalId;
        PostingResult replay = replay(key, fingerprint);
        if (replay != null) return replay;
        LedgerTransaction original = transactions.findById(originalId)
                .orElseThrow(() -> LedgerException.missing("Transaction " + originalId + " does not exist"));
        if (original.getReversalOf() != null) {
            throw LedgerException.conflict("REVERSAL_OF_REVERSAL", "A reversal cannot itself be reversed");
        }
        if (transactions.existsByReversalOf(originalId)) {
            throw LedgerException.conflict("ALREADY_REVERSED", "This transaction has already been reversed");
        }
        return post(key, fingerprint, account(locked, original.getDestinationAccountId()),
                account(locked, original.getSourceAccountId()), original.getDestinationAmount(), original.getSourceAmount(),
                original.getExchangeRate(), originalId);
    }

    private PostingResult post(String key, String fingerprint, Account source, Account destination,
            BigDecimal sourceAmount, BigDecimal destinationAmount, BigDecimal rate, Long reversalOf) {
        if (source.getBalance().compareTo(sourceAmount) < 0) {
            throw LedgerException.unprocessable("INSUFFICIENT_FUNDS", "Source account has insufficient funds");
        }
        if (destination.getBalance().add(destinationAmount).compareTo(Money.MAX) > 0) {
            throw LedgerException.unprocessable("BALANCE_LIMIT", "Destination balance would exceed the supported range");
        }
        source.debit(sourceAmount);
        destination.credit(destinationAmount);

        LedgerTransaction transaction = new LedgerTransaction(source.getId(), destination.getId(),
                source.getCurrency(), destination.getCurrency(), sourceAmount, destinationAmount, rate,
                clock.instant().truncatedTo(ChronoUnit.MICROS), key, fingerprint, reversalOf);
        transactions.save(transaction);
        entries.save(new LedgerEntry(transaction.getId(), source, sourceAmount.negate()));
        entries.save(new LedgerEntry(transaction.getId(), destination, destinationAmount));
        return new PostingResult(TransactionView.of(transaction), false);
    }

    private PostingResult replay(String key, String fingerprint) {
        return transactions.findByIdempotencyKey(key).map(existing -> {
            if (!existing.getRequestFingerprint().equals(fingerprint)) {
                throw LedgerException.conflict("IDEMPOTENCY_CONFLICT", "This key was used for a different request");
            }
            return new PostingResult(TransactionView.of(existing), true);
        }).orElse(null);
    }

    private void validateKey(String key) {
        if (key == null || !key.matches("[\\x21-\\x7E]{1,128}")) {
            throw LedgerException.badRequest("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must contain 1–128 printable non-space ASCII characters");
        }
    }

    private Account account(List<Account> locked, Long id) {
        return locked.stream().filter(a -> a.getId().equals(id)).findFirst()
                .orElseThrow(() -> LedgerException.missing("Account " + id + " does not exist"));
    }

    @Transactional(readOnly = true)
    public AccountView balance(Long id) {
        Account a = accounts.findById(id).orElseThrow(() -> LedgerException.missing("Account " + id + " does not exist"));
        return new AccountView(a.getId(), a.getCurrency(), a.getBalance());
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public HistoryPage history(Long id, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw LedgerException.badRequest("INVALID_PAGE", "page must be nonnegative and size must be between 1 and 100");
        }
        if (!accounts.existsById(id)) throw LedgerException.missing("Account " + id + " does not exist");
        var result = entries.history(id, PageRequest.of(page, size));
        var items = result.map(e -> new HistoryItem(e.getId(), e.getAmount(), e.getBalanceAfter(),
                TransactionView.of(transactions.findById(e.getTransactionId()).orElseThrow()))).getContent();
        return new HistoryPage(items, page, size, result.getTotalElements(), result.getTotalPages());
    }
}
