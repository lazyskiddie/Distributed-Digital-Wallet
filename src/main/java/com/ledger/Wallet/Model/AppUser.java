package com.ledger.Wallet.Model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "app_users")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String fullName;

    // Always stored lower-case and trimmed.
    @Column(nullable = false, unique = true)
    private String email;

    // "pbkdf2_sha256$iterations$salt$hash". The plain password is never stored.
    @Column(nullable = false)
    private String passwordHash;

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime createdAt;
}

