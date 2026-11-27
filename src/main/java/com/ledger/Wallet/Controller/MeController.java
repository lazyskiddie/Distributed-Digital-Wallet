package com.ledger.Wallet.Controller;

import com.ledger.Wallet.Exception.ApiException;
import com.ledger.Wallet.Model.Account;
import com.ledger.Wallet.Model.AppUser;
import com.ledger.Wallet.Model.AuthInterceptor;
import com.ledger.Wallet.Service.WalletService;
import com.ledger.Wallet.dto.ActivityView;
import com.ledger.Wallet.dto.DepositRequest;
import com.ledger.Wallet.dto.DepositResult;
import com.ledger.Wallet.dto.ProfileView;
import com.ledger.Wallet.dto.RecipientView;
import com.ledger.Wallet.dto.SendMoneyRequest;
import com.ledger.Wallet.dto.SendResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

// The signed-in user's own wallet. The user always comes from the session, never from the request
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class MeController {

    private final WalletService wallets;

    @GetMapping
    public ProfileView me(@RequestAttribute(AuthInterceptor.USER_ATTR) AppUser user) {
        return wallets.profile(user);
    }

    @GetMapping("/transactions")
    public List<ActivityView> transactions(@RequestAttribute(AuthInterceptor.USER_ATTR) AppUser user) {
        return wallets.activity(wallets.walletOf(user).getAccountNumber());
    }

    @PostMapping("/deposit")
    public DepositResult deposit(@RequestAttribute(AuthInterceptor.USER_ATTR) AppUser user,
                                 @Valid @RequestBody DepositRequest body) {
        return wallets.deposit(user, body.amount());
    }

    @PostMapping("/transfers")
    public SendResult send(@RequestAttribute(AuthInterceptor.USER_ATTR) AppUser user,
                           @Valid @RequestBody SendMoneyRequest body) {
        return wallets.send(user, body.recipient(), body.amount());
    }

    // Lets the sender confirm who they are paying before sending
    @GetMapping("/recipient")
    public RecipientView recipient(@RequestAttribute(AuthInterceptor.USER_ATTR) AppUser user,
                                   @RequestParam("q") String query) {
        Account found = wallets.findRecipient(query).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "No wallet found for that email or wallet number."));
        return new RecipientView(WalletService.displayName(found), found.getAccountNumber());
    }
}
