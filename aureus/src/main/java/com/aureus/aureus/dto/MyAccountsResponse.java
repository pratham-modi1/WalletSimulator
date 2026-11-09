package com.aureus.aureus.dto;

import java.math.BigDecimal;

public class MyAccountsResponse {

    private BigDecimal bankBalance;
    private BigDecimal walletBalance;

    public MyAccountsResponse() {
    }

    public MyAccountsResponse(
            BigDecimal bankBalance,
            BigDecimal walletBalance) {

        this.bankBalance = bankBalance;
        this.walletBalance = walletBalance;
    }

    public BigDecimal getBankBalance() {
        return bankBalance;
    }

    public void setBankBalance(BigDecimal bankBalance) {
        this.bankBalance = bankBalance;
    }

    public BigDecimal getWalletBalance() {
        return walletBalance;
    }

    public void setWalletBalance(BigDecimal walletBalance) {
        this.walletBalance = walletBalance;
    }
}