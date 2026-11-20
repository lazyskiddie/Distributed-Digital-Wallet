package com.ledger.Wallet.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// One line of a user's history. direction is "IN" (received / added) or "OUT" (sent)
public record ActivityView(
        String transactionReference,
        String direction,
        String counterparty,
        BigDecimal amount,
        String status,
        LocalDateTime timestamp
) {}