package com.example.ledger.service.fx;

import com.example.ledger.config.FxProperties;
import com.example.ledger.exception.LedgerException;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredExchangeRateProvider implements ExchangeRateProvider {
    private final FxProperties properties;

    public ConfiguredExchangeRateProvider(FxProperties properties) {
        this.properties = properties;
    }

    @Override
    public BigDecimal rate(String source, String destination) {
        if (!(source.equals("USD") || source.equals("SGD"))
                || !(destination.equals("USD") || destination.equals("SGD"))) {
            throw LedgerException.unprocessable("UNSUPPORTED_CURRENCY", "Only USD and SGD are supported");
        }
        if (source.equals(destination)) return BigDecimal.ONE;
        return source.equals("USD") ? properties.usdToSgd() : properties.sgdToUsd();
    }
}
