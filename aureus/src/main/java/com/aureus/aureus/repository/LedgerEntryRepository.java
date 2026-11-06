package com.aureus.aureus.repository;

import com.aureus.aureus.entity.LedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerEntryRepository
        extends JpaRepository<LedgerEntry, Integer> {
}