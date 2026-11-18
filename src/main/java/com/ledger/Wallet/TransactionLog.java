package com.ledger.Wallet;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "transaction_logs")
public class TransactionLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String transactionReference; // A unique UUID for tracking

    @Column(nullable = false)
    private String sourceAccount;

    @Column(nullable = false)
    private String destinationAccount;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false)
    private String status; // SUCCESS or FAILED

    @CreationTimestamp
    @Column(updatable = false)
    private LocalDateTime timestamp;
}
