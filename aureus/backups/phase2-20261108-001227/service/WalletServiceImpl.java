package com.aureus.aureus.service;

import com.aureus.aureus.dto.MoneyResponse;
import com.aureus.aureus.dto.MoneyResult;
import com.aureus.aureus.entity.TransactionStatus;
import com.aureus.aureus.entity.TransactionType;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;

@Service
public class WalletServiceImpl implements WalletService {

    @Override
    public MoneyResult addMoney(Integer userId,
                                BigDecimal amount,
                                String clientKey) {

        MoneyResponse response = new MoneyResponse(
                1,
                TransactionType.ADD_MONEY,
                amount,
                TransactionStatus.COMPLETED,
                new BigDecimal("2000.00"),
                new BigDecimal("3000.00")
        );

        return new MoneyResult(response, false);
    }

    @Override
    public MoneyResult transferToBank(Integer userId,
                                       BigDecimal amount,
                                       String clientKey) {

        MoneyResponse response = new MoneyResponse(
                2,
                TransactionType.WITHDRAWAL,
                amount,
                TransactionStatus.COMPLETED,
                new BigDecimal("3000.00"),
                new BigDecimal("2000.00")
        );

        return new MoneyResult(response, false);
    }
}
