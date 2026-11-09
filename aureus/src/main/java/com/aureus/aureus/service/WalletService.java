
package com.aureus.aureus.service;

import com.aureus.aureus.dto.MoneyResult;
import com.aureus.aureus.dto.SendMoneyResult;

import java.math.BigDecimal;

public interface WalletService {

    MoneyResult addMoney(
            Integer userId,
            BigDecimal amount,
            String clientKey
    );

    MoneyResult transferToBank(
            Integer userId,
            BigDecimal amount,
            String clientKey
    );

    SendMoneyResult sendMoney(
            Integer senderUserId,
            String recipientEmail,
            BigDecimal amount,
            String clientKey
    );
}
