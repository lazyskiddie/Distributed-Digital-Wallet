package com.ledger.Wallet.Service;

import com.ledger.Wallet.Model.Account;
import com.ledger.Wallet.Exception.ApiException;
import com.ledger.Wallet.Model.AppUser;
import com.ledger.Wallet.Repository.AccountRepository;
import com.ledger.Wallet.Repository.TransactionLogRepository;
import com.ledger.Wallet.Repository.UserRepository;
import com.ledger.Wallet.Model.TransactionLog;
import com.ledger.Wallet.dto.ActivityView;
import com.ledger.Wallet.dto.DepositResult;
import com.ledger.Wallet.dto.ProfileView;
import com.ledger.Wallet.dto.SendResult;
import com.ledger.Wallet.dto.TransferRequest;
import com.ledger.Wallet.dto.WalletView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

// Everything a signed-in user can do with their own wallet
@Service
public class WalletService {

    // Pseudo source account used in the history for demo top-ups
    public static final String TOP_UP = "TOP-UP";
    private static final String INSUFFICIENT = "Insufficient funds";

    private final AccountRepository accounts;
    private final UserRepository users;
    private final TransactionLogRepository logs;
    private final TransactionLogService logService;
    private final TransferService transferService;
    private final TransactionTemplate tx;

    @Autowired
    public WalletService(AccountRepository accounts, UserRepository users, TransactionLogRepository logs,
                         TransactionLogService logService, TransferService transferService,
                         PlatformTransactionManager transactionManager) {
        this.accounts = accounts;
        this.users = users;
        this.logs = logs;
        this.logService = logService;
        this.transferService = transferService;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public Account walletOf(AppUser user) {
        return accounts.findByUserId(user.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Wallet not found."));
    }

    public ProfileView profile(AppUser user) {
        Account wallet = walletOf(user);
        return new ProfileView(user.getFullName(), user.getEmail(),
                new WalletView(wallet.getAccountNumber(), wallet.getBalance()));
    }

    // Demo top-up: adds funds without any real payment. Recorded in the history like any other movement
    public DepositResult deposit(AppUser user, BigDecimal amount) {
        String number = walletOf(user).getAccountNumber();
        String reference = UUID.randomUUID().toString();

        tx.executeWithoutResult(status -> {
            if (accounts.credit(number, amount, LocalDateTime.now()) == 0) {
                throw new ApiException(HttpStatus.NOT_FOUND, "Wallet not found.");
            }
            logService.record(new TransferRequest(TOP_UP, number, amount), reference, "SUCCESS");
        });
        return new DepositResult(reference, amount, walletOf(user).getBalance());
    }

    // Finds a wallet by the owner's email or by wallet number
    public Optional<Account> findRecipient(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return Optional.empty();
        }
        if (q.contains("@")) {
            return users.findByEmail(q.toLowerCase(Locale.ROOT)).flatMap(u -> accounts.findByUserId(u.getId()));
        }
        return accounts.findByAccountNumber(q.toUpperCase(Locale.ROOT));
    }

    public static String displayName(Account account) {
        return account.getOwnerName() != null ? account.getOwnerName() : account.getAccountNumber();
    }

    public SendResult send(AppUser sender, String recipientQuery, BigDecimal amount) {
        Account from = walletOf(sender);
        Account to = findRecipient(recipientQuery).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                "We couldn't find that recipient. Check the email or wallet number."));
        if (from.getAccountNumber().equals(to.getAccountNumber())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "You can't send money to yourself.");
        }

        String reference;
        try {
            reference = transferService.transferFunds(
                    new TransferRequest(from.getAccountNumber(), to.getAccountNumber(), amount));
        } catch (IllegalArgumentException e) {
            if (INSUFFICIENT.equals(e.getMessage())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "You don't have enough balance for this transfer.");
            }
            throw e;
        }
        return new SendResult(reference, displayName(to), amount, walletOf(sender).getBalance());
    }

    public List<ActivityView> activity(String walletNumber) {
        List<TransactionLog> rows = logs.findRecentForWallet(walletNumber, PageRequest.of(0, 30));

        Set<String> others = new HashSet<>();
        for (TransactionLog r : rows) {
            others.add(walletNumber.equals(r.getSourceAccount()) ? r.getDestinationAccount() : r.getSourceAccount());
        }
        Map<String, String> names = accounts.findAllByAccountNumberIn(others).stream()
                .collect(Collectors.toMap(Account::getAccountNumber, WalletService::displayName));

        return rows.stream().map(r -> {
            boolean out = walletNumber.equals(r.getSourceAccount());
            String other = out ? r.getDestinationAccount() : r.getSourceAccount();
            String name = TOP_UP.equals(other) ? "Added money" : names.getOrDefault(other, other);
            return new ActivityView(r.getTransactionReference(), out ? "OUT" : "IN", name,
                    r.getAmount(), r.getStatus(), r.getTimestamp());
        }).toList();
    }
}
