package com.example.ledger.service;

import com.example.ledger.exception.LedgerException;
import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    public static final BigDecimal MAX = new BigDecimal("99999999999999999.99");
    private Money() {}

    public static BigDecimal amount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.scale() > 2 || amount.compareTo(MAX) > 0) {
            throw LedgerException.badRequest("INVALID_AMOUNT", "Amount must be positive, with at most 17 integer and 2 decimal digits");
        }
        return amount.setScale(2);
    }

    public static BigDecimal convert(BigDecimal amount, BigDecimal rate) {
        BigDecimal converted = amount.multiply(rate).setScale(2, RoundingMode.HALF_EVEN);
        if (converted.signum() <= 0 || converted.compareTo(MAX) > 0) {
            throw LedgerException.unprocessable("INVALID_CONVERSION", "Converted amount is zero or exceeds the supported range");
        }
        return converted;
    }
}
