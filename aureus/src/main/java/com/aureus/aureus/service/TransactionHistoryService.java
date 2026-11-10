package com.aureus.aureus.service;

import com.aureus.aureus.dto.PageResponse;
import com.aureus.aureus.dto.TransactionView;

import com.aureus.aureus.entity.TransactionType;
import com.aureus.aureus.entity.TransactionStatus;

import java.time.LocalDate;

public interface TransactionHistoryService {

    PageResponse<TransactionView> getTransactionHistory(
            Integer userId,
            int page,
            int size,
            TransactionType type,
            TransactionStatus status,
            LocalDate from,
            LocalDate to
    );


    TransactionView getTransactionById(
            Integer userId,
            Integer transactionId
    );

}