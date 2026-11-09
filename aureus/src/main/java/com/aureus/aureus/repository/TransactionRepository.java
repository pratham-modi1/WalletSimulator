package com.aureus.aureus.repository;

import com.aureus.aureus.entity.Transaction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TransactionRepository
        extends JpaRepository<Transaction, Integer> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
}