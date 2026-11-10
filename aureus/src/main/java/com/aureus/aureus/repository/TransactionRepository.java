package com.aureus.aureus.repository;

import com.aureus.aureus.entity.Transaction;
import com.aureus.aureus.entity.TransactionStatus;
import com.aureus.aureus.entity.TransactionType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface TransactionRepository
        extends JpaRepository<Transaction, Integer> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    /*
     * Retrieves transactions involving any of the supplied accounts.
     *
     * Supports optional filters:
     * - Transaction type
     * - Transaction status
     * - Start date/time (inclusive)
     * - End date/time (exclusive)
     *
     * Pageable provides pagination and sorting.
     */
    @Query(
        value = """
            SELECT t
            FROM Transaction t
            WHERE (
                t.senderAccountId IN :accountIds
                OR t.receiverAccountId IN :accountIds
            )
            AND (:type IS NULL OR t.transactionType = :type)
            AND (:status IS NULL OR t.transactionStatus = :status)
            AND (
                :fromDateTime IS NULL
                OR t.transactionDateTime >= :fromDateTime
            )
            AND (
                :toExclusiveDateTime IS NULL
                OR t.transactionDateTime < :toExclusiveDateTime
            )
            """,
        countQuery = """
            SELECT COUNT(t)
            FROM Transaction t
            WHERE (
                t.senderAccountId IN :accountIds
                OR t.receiverAccountId IN :accountIds
            )
            AND (:type IS NULL OR t.transactionType = :type)
            AND (:status IS NULL OR t.transactionStatus = :status)
            AND (
                :fromDateTime IS NULL
                OR t.transactionDateTime >= :fromDateTime
            )
            AND (
                :toExclusiveDateTime IS NULL
                OR t.transactionDateTime < :toExclusiveDateTime
            )
            """
    )
    Page<Transaction> findHistoryForAccounts(
            @Param("accountIds") List<Integer> accountIds,
            @Param("type") TransactionType type,
            @Param("status") TransactionStatus status,
            @Param("fromDateTime") LocalDateTime fromDateTime,
            @Param("toExclusiveDateTime") LocalDateTime toExclusiveDateTime,
            Pageable pageable
    );
}