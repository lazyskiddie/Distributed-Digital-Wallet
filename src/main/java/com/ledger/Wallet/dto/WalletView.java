package com.ledger.Wallet.dto;

import java.math.BigDecimal;

public record WalletView(String accountNumber, BigDecimal balance) {}

