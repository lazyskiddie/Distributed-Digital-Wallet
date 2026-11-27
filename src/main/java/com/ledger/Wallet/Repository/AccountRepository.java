package com.ledger.Wallet.Repository;

import com.ledger.Wallet.Model.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AccountRepository extends JpaRepository<Account, Long> {
    Optional<Account> findByAccountNumber(String accountNumber);

    boolean existsByAccountNumber(String accountNumber);

    Optional<Account> findByUserId(Long userId);

    List<Account> findAllByAccountNumberIn(Collection<String> accountNumbers);

     // Atomic, race-free debit: the balance check and the subtraction happen in ONE statement,
     // so two concurrent transfers can never both spend the same money. Returns the number of
     // rows changed (0 = account missing OR insufficient funds).
    @Modifying
    @Query("UPDATE Account a SET a.balance = a.balance - :amount, a.updatedAt = :now "
            + "WHERE a.accountNumber = :accountNumber AND a.balance >= :amount")
    int debitIfSufficient(@Param("accountNumber") String accountNumber,
                          @Param("amount") BigDecimal amount,
                          @Param("now") LocalDateTime now);

    // Atomic credit. Returns the number of rows changed (0 = account missing)
    @Modifying
    @Query("UPDATE Account a SET a.balance = a.balance + :amount, a.updatedAt = :now "
            + "WHERE a.accountNumber = :accountNumber")
    int credit(@Param("accountNumber") String accountNumber,
               @Param("amount") BigDecimal amount,
               @Param("now") LocalDateTime now);
}