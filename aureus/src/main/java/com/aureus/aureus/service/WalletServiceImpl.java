
package com.aureus.aureus.service;

import com.aureus.aureus.dto.MoneyResult;
import com.aureus.aureus.dto.SendMoneyResult;

import com.aureus.aureus.entity.AccountType;
import com.aureus.aureus.entity.TransactionType;

import com.aureus.aureus.exception.AccountNotFoundException;
import com.aureus.aureus.exception.InvalidRequestException;

import com.aureus.aureus.repository.AccountRepository;
import com.aureus.aureus.repository.UserRepository;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Locale;

@Service
public class WalletServiceImpl implements WalletService {

    private final LedgerPostingService ledgerPostingService;
    private final UserRepository userRepository;
    private final AccountRepository accountRepository;

    public WalletServiceImpl(
            LedgerPostingService ledgerPostingService,
            UserRepository userRepository,
            AccountRepository accountRepository) {

        this.ledgerPostingService = ledgerPostingService;
        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
    }


    @Override
    public MoneyResult addMoney(
            Integer userId,
            BigDecimal amount,
            String clientKey) {

        return ledgerPostingService.postMoneyMovement(
                userId,
                amount,
                clientKey,
                TransactionType.ADD_MONEY
        );
    }


    @Override
    public MoneyResult transferToBank(
            Integer userId,
            BigDecimal amount,
            String clientKey) {

        return ledgerPostingService.postMoneyMovement(
                userId,
                amount,
                clientKey,
                TransactionType.WITHDRAWAL
        );
    }


    @Override
    public SendMoneyResult sendMoney(
            Integer senderUserId,
            String recipientEmail,
            BigDecimal amount,
            String clientKey) {

        // STEP 1: Normalize recipient email.

        String normalizedEmail =
                recipientEmail.trim().toLowerCase(Locale.ROOT);


        // STEP 2: Find recipient user ID.

        Integer recipientUserId =
                userRepository.findUserIdByEmail(normalizedEmail)
                        .orElseThrow(() ->
                                new AccountNotFoundException(
                                        "Recipient not found"
                                )
                        );


        // STEP 3: Prevent self-transfer.

        if (senderUserId.equals(recipientUserId)) {
            throw new InvalidRequestException(
                    "Cannot send money to yourself"
            );
        }


        // STEP 4: Find sender wallet account ID.

        Integer senderWalletId =
                accountRepository.findAccountIdByUserIdAndType(
                        senderUserId,
                        AccountType.WALLET
                ).orElseThrow(() ->
                        new AccountNotFoundException(
                                "Sender wallet not found"
                        )
                );


        // STEP 5: Find recipient wallet account ID.

        Integer recipientWalletId =
                accountRepository.findAccountIdByUserIdAndType(
                        recipientUserId,
                        AccountType.WALLET
                ).orElseThrow(() ->
                        new AccountNotFoundException(
                                "Recipient not found"
                        )
                );


        // STEP 6: Delegate actual transfer to transactional service.

        return ledgerPostingService.postTransfer(
                senderUserId,
                senderWalletId,
                recipientUserId,
                recipientWalletId,
                normalizedEmail,
                amount,
                clientKey
        );
    }
}
