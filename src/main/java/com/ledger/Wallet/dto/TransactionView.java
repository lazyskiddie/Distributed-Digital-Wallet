package com.ledger.Wallet.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TransactionView(
        String transactionReference,
        String sourceAccount,
        String destinationAccount,
        BigDecimal amount,
        String status,
        LocalDateTime timestamp
) {}