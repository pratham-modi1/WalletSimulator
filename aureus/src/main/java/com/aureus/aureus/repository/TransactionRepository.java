package com.aureus.aureus.repository;

import com.aureus.aureus.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository
        extends JpaRepository<Transaction, Integer> {
}