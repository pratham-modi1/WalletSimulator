package com.aureus.aureus.dto;

import java.math.BigDecimal;

public class RegisterResponse {

    private Integer userId;
    private String email;
    private BigDecimal bankBalance;
    private BigDecimal walletBalance;

    public RegisterResponse(
            Integer userId,
            String email,
            BigDecimal bankBalance,
            BigDecimal walletBalance) {
        this.userId = userId;
        this.email = email;
        this.bankBalance = bankBalance;
        this.walletBalance = walletBalance;
    }

    public Integer getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public BigDecimal getBankBalance() {
        return bankBalance;
    }

    public BigDecimal getWalletBalance() {
        return walletBalance;
    }
}
