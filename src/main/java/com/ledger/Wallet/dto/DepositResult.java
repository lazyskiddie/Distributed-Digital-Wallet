package com.ledger.Wallet.dto;

import java.math.BigDecimal;

public record DepositResult(String transactionReference, BigDecimal amount, BigDecimal balance) {}

