package com.ledger.Wallet.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record DepositRequest(
        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @DecimalMax(value = "100000.00", message = "You can add up to 1,00,000 at a time")
        @Digits(integer = 6, fraction = 2, message = "Amount can have at most 2 decimal places")
        BigDecimal amount
) {}