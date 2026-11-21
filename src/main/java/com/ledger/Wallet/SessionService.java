package com.ledger.Wallet;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SessionService {

    public static final Duration LIFETIME = Duration.ofDays(7);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SessionRepository sessions;
    private final UserRepository users;

    // Starts a session and returns the secret token to put in the cookie.
    @Transactional
    public String create(Long userId) {
        Instant now = Instant.now();
        sessions.deleteByExpiresAtBefore(now); // housekeeping

        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        sessions.save(SessionToken.builder()
                .tokenHash(hash(token))
                .userId(userId)
                .createdAt(now)
                .expiresAt(now.plus(LIFETIME))
                .build());
        return token;
    }

    @Transactional
    public Optional<AppUser> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String hash = hash(token);
        Optional<SessionToken> session = sessions.findById(hash);
        if (session.isEmpty()) {
            return Optional.empty();
        }
        if (session.get().getExpiresAt().isBefore(Instant.now())) {
            sessions.deleteById(hash);
            return Optional.empty();
        }
        return users.findById(session.get().getUserId());
    }

    @Transactional
    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            sessions.deleteById(hash(token));
        }
    }

    private static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

