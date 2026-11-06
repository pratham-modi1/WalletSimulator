package com.aureus.aureus.entity;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "`Transaction`")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transaction_id")
    private Integer transactionId;

    @Column(name = "sender_account_id", nullable = false)
    private Integer senderAccountId;

    @Column(name = "receiver_account_id", nullable = false)
    private Integer receiverAccountId;

    @Column(name = "amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false, length = 15)
    private TransactionType transactionType;

    @Column(
        name = "transaction_date_time",
        insertable = false,
        updatable = false
    )
    private LocalDateTime transactionDateTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_status", nullable = false, length = 10)
    private TransactionStatus transactionStatus;

    @Column(
        name = "idempotency_key",
        nullable = false,
        unique = true,
        length = 100
    )
    private String idempotencyKey;

    @Column(name = "original_transaction_id")
    private Integer originalTransactionId;

    protected Transaction() {
    }

    public Transaction(
            Integer senderAccountId,
            Integer receiverAccountId,
            BigDecimal amount,
            TransactionType transactionType,
            TransactionStatus transactionStatus,
            String idempotencyKey,
            Integer originalTransactionId) {

        this.senderAccountId = senderAccountId;
        this.receiverAccountId = receiverAccountId;
        this.amount = amount;
        this.transactionType = transactionType;
        this.transactionStatus = transactionStatus;
        this.idempotencyKey = idempotencyKey;
        this.originalTransactionId = originalTransactionId;
    }

    public Integer getTransactionId() {
        return transactionId;
    }

    public Integer getSenderAccountId() {
        return senderAccountId;
    }

    public Integer getReceiverAccountId() {
        return receiverAccountId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public LocalDateTime getTransactionDateTime() {
        return transactionDateTime;
    }

    public TransactionStatus getTransactionStatus() {
        return transactionStatus;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Integer getOriginalTransactionId() {
        return originalTransactionId;
    }
}