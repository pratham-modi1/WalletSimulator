package com.aureus.aureus.service;

import com.aureus.aureus.dto.MyAccountsResponse;

import com.aureus.aureus.entity.Account;
import com.aureus.aureus.entity.AccountType;

import com.aureus.aureus.repository.AccountRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;

    public AccountServiceImpl(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public MyAccountsResponse getMyAccounts(Integer userId) {

        // STEP 1: Fetch accounts belonging to this user.
        List<Account> accounts =
                accountRepository.findByUserId(userId);

        BigDecimal bankBalance = null;
        BigDecimal walletBalance = null;

        // STEP 2: Identify the BANK and WALLET accounts.
        for (Account account : accounts) {

            if (account.getAccountType() == AccountType.BANK) {
                bankBalance = account.getBalance();
            }

            else if (account.getAccountType() == AccountType.WALLET) {
                walletBalance = account.getBalance();
            }
        }

        // STEP 3: Verify both customer accounts exist.
        if (bankBalance == null || walletBalance == null) {
            throw new IllegalStateException(
                    "Customer BANK or WALLET account is missing"
            );
        }

        // STEP 4: Build response.
        return new MyAccountsResponse(
                bankBalance,
                walletBalance
        );
    }
}