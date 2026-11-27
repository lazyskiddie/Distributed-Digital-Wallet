package com.ledger.Wallet.Model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

// A signed-in session. Only the SHA-256 hash of the cookie token is stored, never the token itself.
@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "user_sessions")
public class SessionToken {

    @Id
    @Column(length = 64)
    private String tokenHash;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;
}
