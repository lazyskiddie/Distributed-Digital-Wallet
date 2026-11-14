package com.ledger.Wallet;

import com.ledger.Wallet.dto.TransferRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferService {
    private final AccountRepository accountRepository;
    private final TransactionLogRepository transactionLogRepository;

    @Transactional
    public String transferFunds(TransferRequest request) {
        String sourceId = request.sourceAccount();
        String destId = request.destinationAccount();
        String transactionRef = UUID.randomUUID().toString();

        if (sourceId.equals(destId)) {
            throw new IllegalArgumentException("Source and destination accounts cannot be the same.");
        }

        log.info("Initiating transfer of ${} from {} to {} [Ref: {}]",
                request.amount(), sourceId, destId, transactionRef);

        // DEADLOCK PREVENTION: Consistent Lock Ordering
        // Always lock the account with the "smaller" account number first,
        // regardless of who is the sender or receiver.
        Account firstLock, secondLock;

        if (sourceId.compareTo(destId) < 0) {
            firstLock = lockAccount(sourceId);
            secondLock = lockAccount(destId);
        } else {
            firstLock = lockAccount(destId);
            secondLock = lockAccount(sourceId);
        }

        // Identify which locked account is the source and which is the destination
        Account sourceAccount = firstLock.getAccountNumber().equals(sourceId) ? firstLock : secondLock;
        Account destinationAccount = firstLock.getAccountNumber().equals(destId) ? firstLock : secondLock;

        try {
            // Perform the domain logic (math)
            sourceAccount.debit(request.amount());
            destinationAccount.credit(request.amount());

            // Save the updated states
            accountRepository.save(sourceAccount);
            accountRepository.save(destinationAccount);

            // Write the immutable audit log
            saveTransactionLog(request, transactionRef, "SUCCESS");

            log.info("Transfer successful [Ref: {}]", transactionRef);
            return transactionRef;

        } catch (Exception e) {
            log.error("Transfer failed [Ref: {}]: {}", transactionRef, e.getMessage());
            // We save the failed attempt (Requires a separate transaction in a real app, but simplified here)
            saveTransactionLog(request, transactionRef, "FAILED");
            throw e; // Rethrow to trigger the database rollback for the account balances
        }
    }
    private Account lockAccount(String accountNumber) {
        return accountRepository.findByAccountNumberForUpdate(accountNumber)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + accountNumber));
    }

    private void saveTransactionLog(TransferRequest request, String ref, String status) {
        TransactionLog logEntry = TransactionLog.builder()
                .transactionReference(ref)
                .sourceAccount(request.sourceAccount())
                .destinationAccount(request.destinationAccount())
                .amount(request.amount())
                .status(status)
                .build();
        transactionLogRepository.save(logEntry);
    }
}
