package com.example.ledger.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "accounts")
public class Account {
    @Id private Long id;
    @Column(nullable = false, updatable = false, length = 3) private String currency;
    @Column(nullable = false, updatable = false, precision = 19, scale = 2) private BigDecimal openingBalance;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal balance;
    @Column(nullable = false, updatable = false) private Instant createdAt;

    protected Account() {}

    public void debit(BigDecimal amount) { balance = balance.subtract(amount); }
    public void credit(BigDecimal amount) { balance = balance.add(amount); }

    public Long getId() { return id; }
    public String getCurrency() { return currency; }
    public BigDecimal getOpeningBalance() { return openingBalance; }
    public BigDecimal getBalance() { return balance; }
    public Instant getCreatedAt() { return createdAt; }
}
