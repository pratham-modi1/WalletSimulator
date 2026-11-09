
package com.aureus.aureus.repository;

import com.aureus.aureus.entity.Account;
import com.aureus.aureus.entity.AccountType;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

    /*
     * Fetch account IDs without loading complete Account entities.
     */
    @Query("""
        SELECT a.accountId
        FROM Account a
        WHERE a.userId = :userId
          AND a.accountType = :accountType
        """)
    Optional<Integer> findAccountIdByUserIdAndType(
            @Param("userId") Integer userId,
            @Param("accountType") AccountType accountType
    );

    /*
     * Existing locking query for customer's BANK and WALLET accounts.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT a
        FROM Account a
        WHERE a.userId = :userId
          AND a.accountType IN :accountTypes
        ORDER BY a.accountId ASC
        """)
    List<Account> findCustomerAccountsForUpdate(
            @Param("userId") Integer userId,
            @Param("accountTypes") List<AccountType> accountTypes
    );

    /*
     * Existing conditional debit method.
     */
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




    
    /*
     * Locks two wallet accounts belonging to potentially
     * different users.
     *
     * Accounts are acquired in ascending account ID order
     * to reduce the possibility of deadlocks.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        SELECT a
        FROM Account a
        WHERE a.accountId IN :accountIds
        ORDER BY a.accountId ASC
        """)
    List<Account> lockWalletAccountsByIdsOrdered(
            @Param("accountIds") List<Integer> accountIds
    );

}

