package com.example.ledger.service;

import com.example.ledger.config.FxProperties;
import com.example.ledger.exception.LedgerException;
import com.example.ledger.service.fx.ConfiguredExchangeRateProvider;
import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyTest {
    @Test
    void acceptsAndNormalizesValidAmounts() {
        assertThat(Money.amount(new BigDecimal("10"))).isEqualTo(new BigDecimal("10.00"));
        assertThat(Money.amount(new BigDecimal("0.01"))).isEqualTo(new BigDecimal("0.01"));
    }

    @Test
    void rejectsInvalidPrecisionSignAndOverflow() {
        for (String value : new String[]{"0", "-1", "1.001", "1.000", "100000000000000000.00"}) {
            assertThatThrownBy(() -> Money.amount(new BigDecimal(value))).isInstanceOf(LedgerException.class);
        }
        assertThatThrownBy(() -> Money.amount(null)).isInstanceOf(LedgerException.class);
    }

    @Test
    void roundsOnlyDestinationWithHalfEven() {
        assertThat(Money.convert(new BigDecimal("0.02"), new BigDecimal("1.25"))).isEqualByComparingTo("0.02");
        assertThat(Money.convert(new BigDecimal("0.06"), new BigDecimal("1.25"))).isEqualByComparingTo("0.08");
        assertThat(Money.convert(new BigDecimal("100.00"), new BigDecimal("0.80"))).isEqualByComparingTo("80.00");
    }

    @Test
    void rejectsConversionThatRoundsToZeroOrOverflows() {
        assertThatThrownBy(() -> Money.convert(new BigDecimal("0.01"), new BigDecimal("0.1")))
                .isInstanceOf(LedgerException.class).hasMessageContaining("zero");
        assertThatThrownBy(() -> Money.convert(Money.MAX, new BigDecimal("2"))).isInstanceOf(LedgerException.class);
    }

    @Test
    void configuredProviderSupportsBothDirectionsAndSameCurrency() {
        var provider = new ConfiguredExchangeRateProvider(new FxProperties(new BigDecimal("1.25"), new BigDecimal("0.80")));
        assertThat(provider.rate("USD", "SGD")).isEqualByComparingTo("1.25");
        assertThat(provider.rate("SGD", "USD")).isEqualByComparingTo("0.80");
        assertThat(provider.rate("USD", "USD")).isEqualByComparingTo("1");
        assertThatThrownBy(() -> provider.rate("EUR", "USD")).isInstanceOf(LedgerException.class);
        assertThatThrownBy(() -> provider.rate("EUR", "EUR")).isInstanceOf(LedgerException.class);
    }
}
