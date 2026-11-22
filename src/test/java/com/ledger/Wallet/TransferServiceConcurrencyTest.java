package com.ledger.Wallet;

import com.ledger.Wallet.Model.Account;
import com.ledger.Wallet.Repository.AccountRepository;
import com.ledger.Wallet.Repository.TransactionLogRepository;
import com.ledger.Wallet.Service.TransferService;
import com.ledger.Wallet.dto.TransferRequest;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringBootTest
class TransferServiceConcurrencyTest {

    @Autowired private TransferService transferService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionLogRepository logRepository;

    private static final String A = "ACC-001";
    private static final String B = "ACC-002";
    private static final String C = "ACC-003";

    @BeforeEach
    void setUp() {
        logRepository.deleteAll();
        accountRepository.deleteAll();
    }

    private void seed(String number, String balance) {
        accountRepository.save(Account.builder().accountNumber(number).balance(new BigDecimal(balance)).build());
    }

    private BigDecimal balanceOf(String number) {
        return accountRepository.findByAccountNumber(number).orElseThrow().getBalance();
    }

    private static void assertMoney(String expected, BigDecimal actual, String message) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual), message + " (was " + actual + ")");
    }

    /** One task per request; all fire at the same instant. Returns {successes, failures}. */
    private int[] fireAll(List<TransferRequest> requests) throws InterruptedException {
        int n = requests.size();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        try {
            for (TransferRequest req : requests) {
                pool.submit(() -> {
                    try {
                        start.await();
                        transferService.transferFunds(req);
                        ok.incrementAndGet();
                    } catch (Exception e) {
                        log.warn("Transfer failed: {}", e.getMessage());
                        failed.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "Timed out: threads are likely deadlocked");
        } finally {
            pool.shutdownNow();
        }
        return new int[]{ok.get(), failed.get()};
    }

    private static TransferRequest req(String from, String to, String amount) {
        return new TransferRequest(from, to, new BigDecimal(amount));
    }

    @Test
    void opposingTransfersDoNotDeadlockOrLoseUpdates() throws InterruptedException {
        seed(A, "1000.0000");
        seed(B, "1000.0000");

        List<TransferRequest> reqs = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            reqs.add(i % 2 == 0 ? req(A, B, "10.00") : req(B, A, "5.00"));
        }
        int[] r = fireAll(reqs);

        assertEquals(100, r[0], "Some threads failed, likely due to a deadlock");
        assertMoney("750", balanceOf(A), "Account A balance is wrong: lost update");
        assertMoney("1250", balanceOf(B), "Account B balance is wrong: lost update");
        assertEquals(100, logRepository.count(), "Every transfer must have exactly one audit row");
    }

    @Test
    void circularTransfersAcrossThreeAccountsDoNotDeadlock() throws InterruptedException {
        seed(A, "1000"); seed(B, "1000"); seed(C, "1000");

        List<TransferRequest> reqs = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            reqs.add(req(A, B, "10"));
            reqs.add(req(B, C, "10"));
            reqs.add(req(C, A, "10"));
        }
        int[] r = fireAll(reqs);

        assertEquals(90, r[0]);
        // Each account sends 300 and receives 300.
        assertMoney("1000", balanceOf(A), "A");
        assertMoney("1000", balanceOf(B), "B");
        assertMoney("1000", balanceOf(C), "C");
    }

    @Test
    void contentionOnLimitedFundsNeverOverdrawsAndOnlyAffordableTransfersSucceed() throws InterruptedException {
        seed(A, "100.00"); // affords exactly 10 transfers of 10
        seed(B, "0.00");

        List<TransferRequest> reqs = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) reqs.add(req(A, B, "10.00"));
        int[] r = fireAll(reqs);

        assertEquals(10, r[0], "Exactly 10 transfers are affordable");
        assertEquals(30, r[1]);
        assertMoney("0", balanceOf(A), "A must be drained exactly to zero, never negative");
        assertMoney("100", balanceOf(B), "B must receive exactly what A lost");

        long success = logRepository.findAll().stream().filter(l -> "SUCCESS".equals(l.getStatus())).count();
        long failed = logRepository.findAll().stream().filter(l -> "FAILED".equals(l.getStatus())).count();
        assertEquals(10, success);
        assertEquals(30, failed, "Failed attempts must be audited even though they rolled back");
    }
}
