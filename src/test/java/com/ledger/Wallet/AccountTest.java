package com.ledger.Wallet;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

// Pure unit tests: no Spring context, no database
class AccountTest {

    private Account accountWith(String balance) {
        return Account.builder().accountNumber("ACC-X").balance(new BigDecimal(balance)).build();
    }

    @Test
    void debitReducesBalance() {
        Account a = accountWith("100.0000");
        a.debit(new BigDecimal("40.25"));
        assertEquals(0, new BigDecimal("59.75").compareTo(a.getBalance()));
    }

    @Test
    void creditIncreasesBalance() {
        Account a = accountWith("100.0000");
        a.credit(new BigDecimal("0.01"));
        assertEquals(0, new BigDecimal("100.01").compareTo(a.getBalance()));
    }

    @Test
    void debitOfEntireBalanceIsAllowedAndLeavesZero() {
        Account a = accountWith("50.00");
        a.debit(new BigDecimal("50.00"));
        assertEquals(0, BigDecimal.ZERO.compareTo(a.getBalance()));
    }

    @Test
    void debitBeyondBalanceThrowsAndLeavesBalanceUntouched() {
        Account a = accountWith("10.00");
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> a.debit(new BigDecimal("10.01")));
        assertEquals("Insufficient funds", ex.getMessage());
        assertEquals(0, new BigDecimal("10.00").compareTo(a.getBalance()));
    }
}
