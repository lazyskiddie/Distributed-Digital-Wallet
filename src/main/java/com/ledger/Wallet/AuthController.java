package com.ledger.Wallet;

import com.ledger.Wallet.dto.LoginRequest;
import com.ledger.Wallet.dto.ProfileView;
import com.ledger.Wallet.dto.RegisterRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService auth;
    private final SessionService sessions;
    private final WalletService wallets;

    @PostMapping("/register")
    public ResponseEntity<ProfileView> register(@Valid @RequestBody RegisterRequest body,
                                                HttpServletRequest request, HttpServletResponse response) {
        AppUser user = auth.register(body);
        SessionCookies.write(request, response, sessions.create(user.getId()));
        return ResponseEntity.status(HttpStatus.CREATED).body(wallets.profile(user));
    }

    @PostMapping("/login")
    public ProfileView login(@Valid @RequestBody LoginRequest body,
                             HttpServletRequest request, HttpServletResponse response) {
        AppUser user = auth.authenticate(body);
        SessionCookies.write(request, response, sessions.create(user.getId()));
        return wallets.profile(user);
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        sessions.revoke(SessionCookies.read(request));
        SessionCookies.clear(request, response);
        return ResponseEntity.noContent().build();
    }
}

