package com.example.ledger.config;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("ledger.fx")
public record FxProperties(
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 18, fraction = 12) BigDecimal usdToSgd,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 18, fraction = 12) BigDecimal sgdToUsd) {}
