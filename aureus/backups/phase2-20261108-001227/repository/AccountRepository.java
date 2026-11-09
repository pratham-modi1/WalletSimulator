package com.aureus.aureus.repository;

import com.aureus.aureus.entity.Account;
import com.aureus.aureus.entity.AccountType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

public interface AccountRepository
        extends JpaRepository<Account, Integer> {

    List<Account> findByUserId(Integer userId);

    Optional<Account> findByUserIdAndAccountType(
            Integer userId,
            AccountType accountType
    );

    List<Account> findByAccountType(AccountType accountType);

  
    @Modifying(
        flushAutomatically = true,
        clearAutomatically = true
    )
    @Query("""
        UPDATE Account a
        SET a.balance = a.balance - :amount
        WHERE a.accountId = :accountId
          AND a.balance >= :amount
        """)
    int debitIfSufficientBalance(
            @Param("accountId") Integer accountId,
            @Param("amount") BigDecimal amount
    );

}