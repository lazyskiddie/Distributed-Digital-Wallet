package com.ledger.Wallet.dto;

import java.math.BigDecimal;

public record AccountView(String accountNumber, String ownerName, BigDecimal balance) {

}
