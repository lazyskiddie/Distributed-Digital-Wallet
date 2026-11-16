package com.ledger.Wallet;

import com.ledger.Wallet.dto.TransferRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransactionLogService transactionLogService;
    private final AccountLockManager accountLockManager;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public TransferService(AccountRepository accountRepository,
                           TransactionLogService transactionLogService,
                           AccountLockManager accountLockManager,
                           PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.transactionLogService = transactionLogService;
        this.accountLockManager = accountLockManager;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Not itself transactional on purpose: the transfer runs in one transaction; if it rolls back,
     * the FAILED audit row is written afterwards in a fresh transaction. This never needs two
     * connections at once, so it cannot starve the connection pool under load.
     */
    public String transferFunds(TransferRequest request) {
        String transactionRef = UUID.randomUUID().toString();
        // Queue here, BEFORE taking a database connection (see AccountLockManager).
        try (AccountLockManager.Held ignored =
                     accountLockManager.acquire(request.sourceAccount(), request.destinationAccount())) {
            try {
                transactionTemplate.executeWithoutResult(status -> doTransfer(request, transactionRef));
                log.info("Transfer successful [Ref: {}]", transactionRef);
                return transactionRef;
            } catch (RuntimeException e) {
                log.error("Transfer failed [Ref: {}]: {}", transactionRef, e.getMessage());
                transactionLogService.record(request, transactionRef, "FAILED");
                throw e;
            }
        }
    }

    private void doTransfer(TransferRequest request, String transactionRef) {
        String sourceId = request.sourceAccount();
        String destId = request.destinationAccount();
        BigDecimal amount = request.amount();

        if (sourceId.equals(destId)) {
            throw new IllegalArgumentException("Source and destination accounts cannot be the same.");
        }

        log.info("Initiating transfer of {} from {} to {} [Ref: {}]",
                amount, sourceId, destId, transactionRef);

        LocalDateTime now = LocalDateTime.now();

        // DEADLOCK PREVENTION: every UPDATE takes a row lock that is held until commit, so we always
        // touch the account with the "smaller" account number first, whatever the direction.
        // Two transfers can then never wait on each other in a cycle.
        if (sourceId.compareTo(destId) < 0) {
            debit(sourceId, amount, now);
            credit(destId, amount, now);
        } else {
            credit(destId, amount, now);
            debit(sourceId, amount, now);
        }

        // Success audit row commits atomically with the balance changes.
        transactionLogService.record(request, transactionRef, "SUCCESS");
    }

    private void debit(String accountNumber, BigDecimal amount, LocalDateTime now) {
        if (accountRepository.debitIfSufficient(accountNumber, amount, now) == 0) {
            if (!accountRepository.existsByAccountNumber(accountNumber)) {
                throw new IllegalArgumentException("Account not found: " + accountNumber);
            }
            throw new IllegalArgumentException("Insufficient funds");
        }
    }

    private void credit(String accountNumber, BigDecimal amount, LocalDateTime now) {
        if (accountRepository.credit(accountNumber, amount, now) == 0) {
            throw new IllegalArgumentException("Account not found: " + accountNumber);
        }
    }
}