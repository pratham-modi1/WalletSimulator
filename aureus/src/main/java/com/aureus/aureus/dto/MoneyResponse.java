package com.aureus.aureus.dto;

import com.aureus.aureus.entity.TransactionStatus;
import com.aureus.aureus.entity.TransactionType;

import java.math.BigDecimal;

public class MoneyResponse {

    private Integer transactionId;
    private TransactionType type;
    private BigDecimal amount;
    private TransactionStatus status;
    private BigDecimal bankBalance;
    private BigDecimal walletBalance;

    public MoneyResponse(Integer transactionId,
                         TransactionType type,
                         BigDecimal amount,
                         TransactionStatus status,
                         BigDecimal bankBalance,
                         BigDecimal walletBalance) {

        this.transactionId = transactionId;
        this.type = type;
        this.amount = amount;
        this.status = status;
        this.bankBalance = bankBalance;
        this.walletBalance = walletBalance;
    }

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

    public BigDecimal getBankBalance() {
        return bankBalance;
    }

    public BigDecimal getWalletBalance() {
        return walletBalance;
    }
}
