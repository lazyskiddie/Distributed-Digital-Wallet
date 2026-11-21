package com.ledger.Wallet.dto;

import java.math.BigDecimal;

public record SendResult(String transactionReference, String recipientName, BigDecimal amount, BigDecimal balance) {

}

