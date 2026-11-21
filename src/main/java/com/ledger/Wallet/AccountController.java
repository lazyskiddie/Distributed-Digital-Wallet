package com.ledger.Wallet;

import com.ledger.Wallet.dto.AccountView;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountRepository accountRepository;

    @GetMapping
    public List<AccountView> listAccounts() {
        return accountRepository.findAll(Sort.by("accountNumber")).stream()
                .map(a -> new AccountView(a.getAccountNumber(), a.getOwnerName(), a.getBalance()))
                .toList();
    }
}