package com.aureus.aureus.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "Account")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "account_id")
    private Integer accountId;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false, length = 6)
    private AccountType accountType;

    @Column(name = "balance", nullable = false, precision = 10, scale = 2)
    private BigDecimal balance;

    // Required by JPA
    protected Account() {
    }

    public Account(Integer userId, AccountType accountType,
                   BigDecimal balance) {
        this.userId = userId;
        this.accountType = accountType;
        this.balance = balance;
    }

    public Integer getAccountId() {
        return accountId;
    }

    public Integer getUserId() {
        return userId;
    }

    public AccountType getAccountType() {
        return accountType;
    }

    public BigDecimal getBalance() {
        return balance;
    }
}