package com.ledger.Wallet.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

// The sender is always the signed-in user; the client cannot choose it
public record SendMoneyRequest(
        @NotBlank(message = "Recipient is required") String recipient,

        @NotNull(message = "Amount is required")
        @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
        @Digits(integer = 12, fraction = 2, message = "Amount can have at most 2 decimal places")
        BigDecimal amount
) {}