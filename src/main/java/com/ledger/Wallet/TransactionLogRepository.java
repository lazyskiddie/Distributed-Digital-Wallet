package com.ledger.Wallet;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionLogRepository extends JpaRepository<TransactionLog, Long> {
    // Activity for one wallet, newest first: everything it sent (including declined attempts) and
     // everything it successfully received. Other people's failed attempts toward this wallet stay private.
    @Query("select t from TransactionLog t where t.sourceAccount = :wallet "
            + "or (t.destinationAccount = :wallet and t.status = 'SUCCESS') order by t.id desc")
    List<TransactionLog> findRecentForWallet(@Param("wallet") String wallet, Pageable pageable);
}
