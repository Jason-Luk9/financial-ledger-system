package com.example.ledger.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "ledger_entries")
public class LedgerEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long transactionId;
    @Column(nullable = false) private Long accountId;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal amount;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal balanceAfter;

    protected LedgerEntry() {}

    public LedgerEntry(Long transactionId, Account account, BigDecimal amount) {
        this.transactionId = transactionId;
        this.accountId = account.getId();
        this.amount = amount;
        this.balanceAfter = account.getBalance();
    }

    public Long getId() { return id; }
    public Long getTransactionId() { return transactionId; }
    public Long getAccountId() { return accountId; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
}
