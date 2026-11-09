package com.aureus.aureus.dto;

import com.aureus.aureus.entity.TransactionType;
import com.aureus.aureus.entity.TransactionStatus;

import java.math.BigDecimal;

public class SendMoneyResponse {

    private Integer transactionId;
    private TransactionType type;
    private BigDecimal amount;
    private TransactionStatus status;
    private String recipientEmail;
    private BigDecimal walletBalance;


    // Constructor

    public SendMoneyResponse(
            Integer transactionId,
            TransactionType type,
            BigDecimal amount,
            TransactionStatus status,
            String recipientEmail,
            BigDecimal walletBalance) {

        this.transactionId = transactionId;
        this.type = type;
        this.amount = amount;
        this.status = status;
        this.recipientEmail = recipientEmail;
        this.walletBalance = walletBalance;
    }


    // Getters

    public Integer getTransactionId() {
        return transactionId;
    }

    public TransactionType getType() {
        return type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public String getRecipientEmail() {
        return recipientEmail;
    }

    public BigDecimal getWalletBalance() {
        return walletBalance;
    }
}