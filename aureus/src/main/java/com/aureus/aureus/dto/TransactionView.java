package com.aureus.aureus.dto;

import com.aureus.aureus.entity.TransactionType;
import com.aureus.aureus.entity.TransactionStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public class TransactionView {

    private Integer transactionId;

    private TransactionType type;

    private TransactionDirection direction;

    private BigDecimal amount;

    private TransactionStatus status;

    private String counterparty;

    private LocalDateTime createdAt;


    public TransactionView(
            Integer transactionId,
            TransactionType type,
            TransactionDirection direction,
            BigDecimal amount,
            TransactionStatus status,
            String counterparty,
            LocalDateTime createdAt) {

        this.transactionId = transactionId;
        this.type = type;
        this.direction = direction;
        this.amount = amount;
        this.status = status;
        this.counterparty = counterparty;
        this.createdAt = createdAt;
    }


    public Integer getTransactionId() {
        return transactionId;
    }

    public TransactionType getType() {
        return type;
    }

    public TransactionDirection getDirection() {
        return direction;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public String getCounterparty() {
        return counterparty;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

}