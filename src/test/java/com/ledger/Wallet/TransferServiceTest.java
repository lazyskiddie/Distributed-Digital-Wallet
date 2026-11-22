package com.ledger.Wallet;

import com.ledger.Wallet.Model.Account;
import com.ledger.Wallet.Model.TransactionLog;
import com.ledger.Wallet.Repository.AccountRepository;
import com.ledger.Wallet.Repository.TransactionLogRepository;
import com.ledger.Wallet.Service.TransferService;
import com.ledger.Wallet.dto.TransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class TransferServiceTest {

    @Autowired private TransferService transferService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionLogRepository logRepository;

    @BeforeEach
    void setUp() {
        logRepository.deleteAll();
        accountRepository.deleteAll();
        seed("ACC-001", "1000.0000");
        seed("ACC-002", "500.0000");
    }

    private void seed(String number, String balance) {
        accountRepository.save(Account.builder().accountNumber(number).balance(new BigDecimal(balance)).build());
    }

    private BigDecimal balanceOf(String number) {
        return accountRepository.findByAccountNumber(number).orElseThrow().getBalance();
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " but was " + actual);
    }

    private List<TransactionLog> logsWithStatus(String status) {
        return logRepository.findAll().stream().filter(l -> status.equals(l.getStatus())).toList();
    }

    @Test
    void successfulTransferMovesMoneyAndWritesSuccessLog() {
        String ref = transferService.transferFunds(new TransferRequest("ACC-001", "ACC-002", new BigDecimal("250.50")));

        assertNotNull(ref);
        assertMoney("749.50", balanceOf("ACC-001"));
        assertMoney("750.50", balanceOf("ACC-002"));

        List<TransactionLog> logs = logsWithStatus("SUCCESS");
        assertEquals(1, logs.size());
        assertEquals(ref, logs.get(0).getTransactionReference());
        assertEquals("ACC-001", logs.get(0).getSourceAccount());
        assertEquals("ACC-002", logs.get(0).getDestinationAccount());
        assertTrue(logsWithStatus("FAILED").isEmpty());
    }

    @Test
    void transferWorksInBothDirections() {
        transferService.transferFunds(new TransferRequest("ACC-002", "ACC-001", new BigDecimal("100")));
        assertMoney("1100", balanceOf("ACC-001"));
        assertMoney("400", balanceOf("ACC-002"));
    }

    @Test
    void transferringEntireBalanceLeavesZero() {
        transferService.transferFunds(new TransferRequest("ACC-002", "ACC-001", new BigDecimal("500.00")));
        assertMoney("0", balanceOf("ACC-002"));
        assertMoney("1500", balanceOf("ACC-001"));
    }

    @Test
    void insufficientFundsRollsBackBalancesButKeepsFailedLog() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> transferService.transferFunds(new TransferRequest("ACC-002", "ACC-001", new BigDecimal("500.01"))));

        assertEquals("Insufficient funds", ex.getMessage());
        assertMoney("1000", balanceOf("ACC-001"));
        assertMoney("500", balanceOf("ACC-002"));
        assertEquals(1, logsWithStatus("FAILED").size(), "FAILED attempt must survive the rollback");
        assertTrue(logsWithStatus("SUCCESS").isEmpty());
    }

    @Test
    void sameSourceAndDestinationIsRejected() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> transferService.transferFunds(new TransferRequest("ACC-001", "ACC-001", new BigDecimal("10"))));
        assertTrue(ex.getMessage().contains("cannot be the same"));
        assertMoney("1000", balanceOf("ACC-001"));
    }

    @Test
    void unknownSourceAccountIsRejectedAndNothingChanges() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> transferService.transferFunds(new TransferRequest("NOPE", "ACC-001", new BigDecimal("10"))));
        assertTrue(ex.getMessage().contains("Account not found: NOPE"));
        assertMoney("1000", balanceOf("ACC-001"));
        assertMoney("500", balanceOf("ACC-002"));
    }

    @Test
    void unknownDestinationDoesNotDebitSource() {
        assertThrows(IllegalArgumentException.class,
                () -> transferService.transferFunds(new TransferRequest("ACC-001", "NOPE", new BigDecimal("10"))));
        assertMoney("1000", balanceOf("ACC-001"));
    }

    @Test
    void totalMoneyIsConservedAcrossMixedTransfers() {
        transferService.transferFunds(new TransferRequest("ACC-001", "ACC-002", new BigDecimal("123.45")));
        transferService.transferFunds(new TransferRequest("ACC-002", "ACC-001", new BigDecimal("0.01")));
        assertThrows(IllegalArgumentException.class,
                () -> transferService.transferFunds(new TransferRequest("ACC-002", "ACC-001", new BigDecimal("99999"))));

        assertMoney("1500", balanceOf("ACC-001").add(balanceOf("ACC-002")));
    }
}
