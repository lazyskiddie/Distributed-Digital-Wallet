package com.ledger.Wallet.Repository;

import com.ledger.Wallet.Model.SessionToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;

@Repository
public interface SessionRepository extends JpaRepository<SessionToken, String> {

    void deleteByExpiresAtBefore(Instant cutoff);
}