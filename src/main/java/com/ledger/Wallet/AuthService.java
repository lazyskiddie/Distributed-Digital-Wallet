package com.ledger.Wallet;

import com.ledger.Wallet.dto.LoginRequest;
import com.ledger.Wallet.dto.RegisterRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Optional;

@Service
public class AuthService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final AccountRepository accounts;
    private final PasswordHasher hasher;
    private final LoginThrottle throttle;
    // Checked when the email is unknown, so "no such user" takes as long as "wrong password".
    private final String dummyHash;

    public AuthService(UserRepository users, AccountRepository accounts,
                       PasswordHasher hasher, LoginThrottle throttle) {
        this.users = users;
        this.accounts = accounts;
        this.hasher = hasher;
        this.throttle = throttle;
        this.dummyHash = hasher.hash("not-a-real-password");
    }

    // Creates the user and their own empty wallet in one transaction
    @Transactional
    public AppUser register(RegisterRequest request) {
        String email = normalizeEmail(request.email());
        String fullName = request.fullName().trim().replaceAll("\\s+", " ");

        if (users.existsByEmail(email)) {
            throw emailTaken();
        }
        AppUser user;
        try {
            user = users.saveAndFlush(AppUser.builder()
                    .fullName(fullName)
                    .email(email)
                    .passwordHash(hasher.hash(request.password()))
                    .build());
        } catch (DataIntegrityViolationException e) { // two sign-ups with the same email at once
            throw emailTaken();
        }

        accounts.save(Account.builder()
                .accountNumber(newWalletNumber())
                .ownerName(fullName)
                .userId(user.getId())
                .balance(BigDecimal.ZERO)
                .build());
        return user;
    }

    public AppUser authenticate(LoginRequest request) {
        String email = normalizeEmail(request.email());
        throttle.check(email);

        Optional<AppUser> user = users.findByEmail(email);
        boolean passwordOk = hasher.matches(request.password(), user.map(AppUser::getPasswordHash).orElse(dummyHash));
        if (user.isEmpty() || !passwordOk) {
            throttle.recordFailure(email);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Incorrect email or password.");
        }
        throttle.reset(email);
        return user.get();
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static ApiException emailTaken() {
        return new ApiException(HttpStatus.CONFLICT, "An account with this email already exists. Try signing in.");
    }

    private String newWalletNumber() {
        for (int i = 0; i < 20; i++) {
            String number = "ACC-" + (10_000_000 + RANDOM.nextInt(90_000_000));
            if (!accounts.existsByAccountNumber(number)) {
                return number;
            }
        }
        throw new IllegalStateException("Could not allocate a wallet number");
    }
}

