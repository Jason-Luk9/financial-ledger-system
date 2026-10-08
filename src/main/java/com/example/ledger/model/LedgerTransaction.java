package com.example.ledger.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "transactions")
public class LedgerTransaction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false) private Long sourceAccountId;
    @Column(nullable = false) private Long destinationAccountId;
    @Column(nullable = false, length = 3) private String sourceCurrency;
    @Column(nullable = false, length = 3) private String destinationCurrency;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal sourceAmount;
    @Column(nullable = false, precision = 19, scale = 2) private BigDecimal destinationAmount;
    @Column(nullable = false, precision = 30, scale = 12) private BigDecimal exchangeRate;
    @Column(nullable = false) private Instant createdAt;
    @Column(nullable = false, unique = true, length = 128) private String idempotencyKey;
    @Column(nullable = false, length = 200) private String requestFingerprint;
    @Column(unique = true) private Long reversalOf;

    protected LedgerTransaction() {}

    public LedgerTransaction(Long sourceAccountId,
            Long destinationAccountId,
            String sourceCurrency,
            String destinationCurrency,
            BigDecimal sourceAmount,
            BigDecimal destinationAmount,
            BigDecimal exchangeRate,
            Instant createdAt,
            String idempotencyKey,
            String requestFingerprint,
            Long reversalOf) {
        this.sourceAccountId = sourceAccountId;
        this.destinationAccountId = destinationAccountId;
        this.sourceCurrency = sourceCurrency;
        this.destinationCurrency = destinationCurrency;
        this.sourceAmount = sourceAmount;
        this.destinationAmount = destinationAmount;
        this.exchangeRate = exchangeRate;
        this.createdAt = createdAt;
        this.idempotencyKey = idempotencyKey;
        this.requestFingerprint = requestFingerprint;
        this.reversalOf = reversalOf;
    }


    public Long getId() { return id; }
    public Long getSourceAccountId() { return sourceAccountId; }
    public Long getDestinationAccountId() { return destinationAccountId; }
    public String getSourceCurrency() { return sourceCurrency; }
    public String getDestinationCurrency() { return destinationCurrency; }
    public BigDecimal getSourceAmount() { return sourceAmount; }
    public BigDecimal getDestinationAmount() { return destinationAmount; }
    public BigDecimal getExchangeRate() { return exchangeRate; }
    public Instant getCreatedAt() { return createdAt; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestFingerprint() { return requestFingerprint; }
    public Long getReversalOf() { return reversalOf; }
}
