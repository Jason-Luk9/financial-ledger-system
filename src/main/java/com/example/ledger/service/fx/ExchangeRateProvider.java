package com.example.ledger.service.fx;

import java.math.BigDecimal;

public interface ExchangeRateProvider {
    BigDecimal rate(String sourceCurrency, String destinationCurrency);
}
