package com.ledger.Wallet;

import com.example.wallet.domain.Account;
import com.example.wallet.dto.TransferRequest;
import com.example.wallet.repository.AccountRepository;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Slf4j
@SpringBootTest
@Testcontainers // Spins up a real PostgreSQL database in Docker just for this test!
class TransferServiceConcurrencyTest {

    // 1. The Testcontainers Database Setup
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private TransferService transferService;

    @Autowired
    private AccountRepository accountRepository;

    private static final String ACCOUNT_A = "ACC-001";
    private static final String ACCOUNT_B = "ACC-002";

    @BeforeEach
    void setUp() {
        // Seed the database with two accounts, each having $1000
        accountRepository.save(Account.builder()
                .accountNumber(ACCOUNT_A)
                .balance(new BigDecimal("1000.0000"))
                .build());

        accountRepository.save(Account.builder()
                .accountNumber(ACCOUNT_B)
                .balance(new BigDecimal("1000.0000"))
                .build());
    }

    @AfterEach
    void tearDown() {
        accountRepository.deleteAll();
    }

    @Test
    void testConcurrentTransfers_ShouldPreventRaceConditionsAndDeadlocks() throws InterruptedException {
        int numberOfThreads = 100;
        ExecutorService executorService = Executors.newFixedThreadPool(numberOfThreads);

        // CountDownLatch ensures all threads start executing at the EXACT same time
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(numberOfThreads);

        AtomicInteger successfulTransfers = new AtomicInteger(0);
        AtomicInteger failedTransfers = new AtomicInteger(0);

        log.info("Starting concurrency test with {} threads...", numberOfThreads);

        for (int i = 0; i < numberOfThreads; i++) {
            final int threadIndex = i;
            executorService.submit(() -> {
                try {
                    // Wait until the starting gun is fired
                    startLatch.await();

                    // Even threads send money A -> B, Odd threads send B -> A
                    // This intentionally attempts to cause a Deadlock!
                    if (threadIndex % 2 == 0) {
                        transferService.transferFunds(new TransferRequest(ACCOUNT_A, ACCOUNT_B, new BigDecimal("10.00")));
                    } else {
                        transferService.transferFunds(new TransferRequest(ACCOUNT_B, ACCOUNT_A, new BigDecimal("5.00")));
                    }
                    successfulTransfers.incrementAndGet();
                } catch (Exception e) {
                    log.error("Transfer failed: {}", e.getMessage());
                    failedTransfers.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        // 🎯 FIRE THE STARTING GUN: Let all 100 threads hit the database simultaneously
        startLatch.countDown();

        // Wait for all threads to finish
        endLatch.await();
        executorService.shutdown();

        log.info("Test completed. Success: {}, Failed: {}", successfulTransfers.get(), failedTransfers.get());

        // ==========================================
        // THE ASSERTIONS (The Moment of Truth)
        // ==========================================
        Account finalAccountA = accountRepository.findByAccountNumber(ACCOUNT_A).orElseThrow();
        Account finalAccountB = accountRepository.findByAccountNumber(ACCOUNT_B).orElseThrow();

        // Account A sent $10 fifty times (-$500), and received $5 fifty times (+$250). Net change: -$250.
        // Final Balance should be exactly $750.
        assertEquals(0, new BigDecimal("750.0000").compareTo(finalAccountA.getBalance()),
                "Account A balance is incorrect. A race condition occurred!");

        // Account B sent $5 fifty times (-$250), and received $10 fifty times (+$500). Net change: +$250.
        // Final Balance should be exactly $1250.
        assertEquals(0, new BigDecimal("1250.0000").compareTo(finalAccountB.getBalance()),
                "Account B balance is incorrect. A race condition occurred!");

        // Ensure no deadlocks crashed the threads
        assertEquals(100, successfulTransfers.get(), "Some threads failed, likely due to a deadlock!");
    }
}
