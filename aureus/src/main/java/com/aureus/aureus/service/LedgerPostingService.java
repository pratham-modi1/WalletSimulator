
package com.aureus.aureus.service;

import com.aureus.aureus.dto.MoneyResponse;
import com.aureus.aureus.dto.MoneyResult;
import com.aureus.aureus.dto.SendMoneyResponse;
import com.aureus.aureus.dto.SendMoneyResult;

import com.aureus.aureus.entity.Account;
import com.aureus.aureus.entity.AccountType;
import com.aureus.aureus.entity.EntryType;
import com.aureus.aureus.entity.LedgerEntry;
import com.aureus.aureus.entity.Transaction;
import com.aureus.aureus.entity.TransactionStatus;
import com.aureus.aureus.entity.TransactionType;
import com.aureus.aureus.entity.User;
import com.aureus.aureus.entity.UserStatus;

import com.aureus.aureus.exception.AccountNotFoundException;
import com.aureus.aureus.exception.IdempotencyConflictException;
import com.aureus.aureus.exception.InsufficientFundsException;
import com.aureus.aureus.exception.InvalidRequestException;

import com.aureus.aureus.repository.AccountRepository;
import com.aureus.aureus.repository.LedgerEntryRepository;
import com.aureus.aureus.repository.TransactionRepository;
import com.aureus.aureus.repository.UserRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class LedgerPostingService {

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final UserRepository userRepository;

    public LedgerPostingService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            LedgerEntryRepository ledgerEntryRepository,
            UserRepository userRepository) {

        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.userRepository = userRepository;
    }


    // =========================================================
    // DAY 5: ADD MONEY AND TRANSFER TO BANK
    // =========================================================

    @Transactional
    public MoneyResult postMoneyMovement(
            Integer userId,
            BigDecimal amount,
            String clientKey,
            TransactionType transactionType) {

        // STEP 1: Validate incoming operation.

        validateInput(
                userId,
                amount,
                clientKey,
                transactionType
        );


        // STEP 2: Construct sender-scoped idempotency key.

        String storedKey = "U" + userId + "-" + clientKey;

        if (storedKey.length() > 100) {
            throw new InvalidRequestException(
                    "Generated idempotency key exceeds database limit"
            );
        }


        // STEP 3: Lock customer's BANK and WALLET accounts FIRST.

        List<Account> accounts =
                accountRepository.findCustomerAccountsForUpdate(
                        userId,
                        List.of(
                                AccountType.BANK,
                                AccountType.WALLET
                        )
                );


        // STEP 4: Verify authenticated user exists and is ACTIVE.

        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new InvalidRequestException(
                                "Authenticated user does not exist"
                        )
                );

        if (user.getUserStatus() != UserStatus.ACTIVE) {
            throw new InvalidRequestException(
                    "User account is not active"
            );
        }


        // STEP 5: Identify BANK and WALLET accounts.

        Account bankAccount = null;
        Account walletAccount = null;

        for (Account account : accounts) {

            if (account.getAccountType() == AccountType.BANK) {

                if (bankAccount != null) {
                    throw new InvalidRequestException(
                            "Multiple BANK accounts found for user"
                    );
                }

                bankAccount = account;
            }

            else if (account.getAccountType() == AccountType.WALLET) {

                if (walletAccount != null) {
                    throw new InvalidRequestException(
                            "Multiple WALLET accounts found for user"
                    );
                }

                walletAccount = account;
            }
        }

        if (bankAccount == null) {
            throw new AccountNotFoundException(
                    "BANK account not found"
            );
        }

        if (walletAccount == null) {
            throw new AccountNotFoundException(
                    "WALLET account not found"
            );
        }


        // STEP 6: Determine source and destination accounts.

        Account sourceAccount;
        Account destinationAccount;

        if (transactionType == TransactionType.ADD_MONEY) {

            sourceAccount = bankAccount;
            destinationAccount = walletAccount;
        }

        else if (transactionType == TransactionType.WITHDRAWAL) {

            sourceAccount = walletAccount;
            destinationAccount = bankAccount;
        }

        else {
            throw new InvalidRequestException(
                    "Unsupported wallet transaction type"
            );
        }


        // STEP 7: Check whether this idempotency key was used before.

        Optional<Transaction> existingTransaction =
                transactionRepository.findByIdempotencyKey(storedKey);

        if (existingTransaction.isPresent()) {

            Transaction previous = existingTransaction.get();

            boolean sameOperation = isSameIdempotencyOperation(
                    previous,
                    transactionType,
                    sourceAccount.getAccountId(),
                    destinationAccount.getAccountId(),
                    amount
            );

            if (!sameOperation
                    || previous.getTransactionStatus()
                    != TransactionStatus.COMPLETED) {

                throw new IdempotencyConflictException(
                        "Idempotency key has already been used "
                                + "for a different transaction"
                );
            }

            // Same key + same operation: return previous result.

            return buildResult(
                    previous,
                    bankAccount,
                    walletAccount,
                    true
            );
        }


        // STEP 8: Verify sufficient balance.

        validateSufficientBalance(sourceAccount, amount);


        // STEP 9: Update source and destination balances.

        updateAccountBalances(
                sourceAccount,
                destinationAccount,
                amount
        );


        // STEP 10: Save transaction record.

        Transaction savedTransaction = saveTransactionRecord(
                sourceAccount,
                destinationAccount,
                amount,
                transactionType,
                storedKey
        );


        // STEP 11: Save DEBIT and CREDIT ledger entries.

        saveDebitAndCreditLedgerEntries(
                savedTransaction,
                sourceAccount,
                destinationAccount,
                amount
        );


        // STEP 12: Build response.

        return buildResult(
                savedTransaction,
                bankAccount,
                walletAccount,
                false
        );
    }


    // =========================================================
    // DAY 6: WALLET-TO-WALLET TRANSFER
    // =========================================================

    @Transactional
    public SendMoneyResult postTransfer(
            Integer senderUserId,
            Integer senderWalletId,
            Integer recipientUserId,
            Integer recipientWalletId,
            String recipientEmail,
            BigDecimal amount,
            String clientKey) {

        // STEP 1: Validate incoming operation.

        validateInput(
                senderUserId,
                amount,
                clientKey,
                TransactionType.TRANSFER
        );

        if (recipientUserId == null
                || senderWalletId == null
                || recipientWalletId == null) {

            throw new InvalidRequestException(
                    "Transfer account information is required"
            );
        }

        if (senderUserId.equals(recipientUserId)) {
            throw new InvalidRequestException(
                    "Cannot send money to yourself"
            );
        }

        if (senderWalletId.equals(recipientWalletId)) {
            throw new InvalidRequestException(
                    "Sender and recipient wallets must be different"
            );
        }


        // STEP 2: Construct sender-scoped idempotency key.

        String storedKey = "U" + senderUserId + "-" + clientKey;

        if (storedKey.length() > 100) {
            throw new InvalidRequestException(
                    "Generated idempotency key exceeds database limit"
            );
        }


        // STEP 3: Sort account IDs in ascending order.

        List<Integer> accountIds = new ArrayList<>(
                List.of(senderWalletId, recipientWalletId)
        );

        accountIds.sort(Comparator.naturalOrder());


        // STEP 4: Lock both wallet accounts.
        // This must be the first database statement.

        List<Account> lockedAccounts =
                accountRepository.lockWalletAccountsByIdsOrdered(
                        accountIds
                );


        // STEP 5: Verify exactly two accounts were locked.

        if (lockedAccounts.size() != 2) {
            throw new AccountNotFoundException(
                    "Transfer wallet account not found"
            );
        }

        Account senderWallet = null;
        Account recipientWallet = null;


        // STEP 6: Validate account types and ownership.

        for (Account account : lockedAccounts) {

            if (account.getAccountType() != AccountType.WALLET) {
                throw new InvalidRequestException(
                        "Transfer requires WALLET accounts"
                );
            }

            if (account.getAccountId().equals(senderWalletId)
                    && account.getUserId().equals(senderUserId)) {

                senderWallet = account;
            }

            if (account.getAccountId().equals(recipientWalletId)
                    && account.getUserId().equals(recipientUserId)) {

                recipientWallet = account;
            }
        }

        if (senderWallet == null || recipientWallet == null) {
            throw new InvalidRequestException(
                    "Wallet ownership validation failed"
            );
        }


        // STEP 7: Load both users AFTER acquiring wallet locks.

        User sender = userRepository.findById(senderUserId)
                .orElseThrow(() ->
                        new InvalidRequestException(
                                "Authenticated user does not exist"
                        )
                );

        User recipient = userRepository.findById(recipientUserId)
                .orElseThrow(() ->
                        new AccountNotFoundException(
                                "Recipient not found"
                        )
                );


        // STEP 8: Verify both users are ACTIVE.

        if (sender.getUserStatus() != UserStatus.ACTIVE) {
            throw new InvalidRequestException(
                    "User account is not active"
            );
        }

        if (recipient.getUserStatus() != UserStatus.ACTIVE) {
            throw new AccountNotFoundException(
                    "Recipient not found"
            );
        }


        // STEP 9: Check whether this idempotency key was used before.

        Optional<Transaction> existingTransaction =
                transactionRepository.findByIdempotencyKey(storedKey);

        if (existingTransaction.isPresent()) {

            Transaction previous = existingTransaction.get();

            boolean sameOperation = isSameIdempotencyOperation(
                    previous,
                    TransactionType.TRANSFER,
                    senderWallet.getAccountId(),
                    recipientWallet.getAccountId(),
                    amount
            );

            if (!sameOperation
                    || previous.getTransactionStatus()
                    != TransactionStatus.COMPLETED) {

                throw new IdempotencyConflictException(
                        "Idempotency key has already been used "
                                + "for a different transaction"
                );
            }

            // Same key + same operation: do not transfer again.

            SendMoneyResponse response = new SendMoneyResponse(
                    previous.getTransactionId(),
                    previous.getTransactionType(),
                    previous.getAmount(),
                    previous.getTransactionStatus(),
                    recipientEmail,
                    senderWallet.getBalance()
            );

            return new SendMoneyResult(response, true);
        }


        // STEP 10: Verify sender has sufficient wallet balance.

        validateSufficientBalance(senderWallet, amount);


        // STEP 11: Update sender and recipient wallet balances.

        updateAccountBalances(
                senderWallet,
                recipientWallet,
                amount
        );


        // STEP 12: Save TRANSFER transaction record.

        Transaction savedTransaction = saveTransactionRecord(
                senderWallet,
                recipientWallet,
                amount,
                TransactionType.TRANSFER,
                storedKey
        );


        // STEP 13: Save DEBIT and CREDIT ledger entries.

        saveDebitAndCreditLedgerEntries(
                savedTransaction,
                senderWallet,
                recipientWallet,
                amount
        );


        // STEP 14: Build SendMoneyResponse.

        SendMoneyResponse response = new SendMoneyResponse(
                savedTransaction.getTransactionId(),
                savedTransaction.getTransactionType(),
                savedTransaction.getAmount(),
                savedTransaction.getTransactionStatus(),
                recipientEmail,
                senderWallet.getBalance()
        );

        return new SendMoneyResult(response, false);
    }


    // =========================================================
    // SHARED REUSABLE HELPER METHODS
    // =========================================================


    /*
     * Checks whether an existing transaction matches the
     * operation being requested now.
     *
     * Compares:
     * - Transaction type
     * - Sender account ID
     * - Receiver account ID
     * - Amount
     */
    private boolean isSameIdempotencyOperation(
            Transaction previous,
            TransactionType expectedType,
            Integer expectedSenderAccountId,
            Integer expectedReceiverAccountId,
            BigDecimal expectedAmount) {

        return previous.getTransactionType() == expectedType

                && Objects.equals(
                        previous.getSenderAccountId(),
                        expectedSenderAccountId
                )

                && Objects.equals(
                        previous.getReceiverAccountId(),
                        expectedReceiverAccountId
                )

                && previous.getAmount().compareTo(expectedAmount) == 0;
    }


    /*
     * Checks whether the source account has enough balance.
     *
     * Throws InsufficientFundsException if the balance is
     * less than the requested amount.
     */
    private void validateSufficientBalance(
            Account sourceAccount,
            BigDecimal amount) {

        if (sourceAccount.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException(
                    "Insufficient balance in source account"
            );
        }
    }


    /*
     * Updates both account balances.
     *
     * Source account      -> DEBIT
     * Destination account -> CREDIT
     *
     * Hibernate dirty checking persists these changes.
     */
    private void updateAccountBalances(
            Account sourceAccount,
            Account destinationAccount,
            BigDecimal amount) {

        BigDecimal newSourceBalance =
                sourceAccount.getBalance().subtract(amount);

        BigDecimal newDestinationBalance =
                destinationAccount.getBalance().add(amount);

        sourceAccount.setBalance(newSourceBalance);
        destinationAccount.setBalance(newDestinationBalance);
    }


    /*
     * Creates and saves a completed transaction record.
     */
    private Transaction saveTransactionRecord(
            Account sourceAccount,
            Account destinationAccount,
            BigDecimal amount,
            TransactionType transactionType,
            String storedKey) {

        Transaction transaction = new Transaction(
                sourceAccount.getAccountId(),
                destinationAccount.getAccountId(),
                amount,
                transactionType,
                TransactionStatus.COMPLETED,
                storedKey,
                null
        );

        return transactionRepository.saveAndFlush(transaction);
    }


    /*
     * Creates the two ledger entries belonging to one transaction.
     *
     * Source account      -> DEBIT
     * Destination account -> CREDIT
     */
    private void saveDebitAndCreditLedgerEntries(
            Transaction savedTransaction,
            Account sourceAccount,
            Account destinationAccount,
            BigDecimal amount) {

        // DEBIT entry.

        ledgerEntryRepository.save(
                new LedgerEntry(
                        savedTransaction.getTransactionId(),
                        sourceAccount.getAccountId(),
                        EntryType.DEBIT,
                        amount
                )
        );


        // CREDIT entry.

        ledgerEntryRepository.save(
                new LedgerEntry(
                        savedTransaction.getTransactionId(),
                        destinationAccount.getAccountId(),
                        EntryType.CREDIT,
                        amount
                )
        );
    }


    // =========================================================
    // COMMON VALIDATION
    // =========================================================

    private void validateInput(
            Integer userId,
            BigDecimal amount,
            String clientKey,
            TransactionType transactionType) {

        if (userId == null) {
            throw new InvalidRequestException(
                    "User ID is required"
            );
        }

        if (amount == null
                || amount.compareTo(BigDecimal.ZERO) <= 0) {

            throw new InvalidRequestException(
                    "Amount must be greater than zero"
            );
        }

        if (amount.scale() > 2
                || amount.precision() - amount.scale() > 8) {

            throw new InvalidRequestException(
                    "Amount exceeds supported precision"
            );
        }

        if (clientKey == null || clientKey.isBlank()) {
            throw new InvalidRequestException(
                    "Idempotency key is required"
            );
        }

        if (clientKey.length() > 64) {
            throw new InvalidRequestException(
                    "Idempotency key cannot exceed 64 characters"
            );
        }

        if (transactionType == null) {
            throw new InvalidRequestException(
                    "Transaction type is required"
            );
        }
    }


    // =========================================================
    // DAY 5 RESPONSE BUILDER
    // =========================================================

    private MoneyResult buildResult(
            Transaction transaction,
            Account bankAccount,
            Account walletAccount,
            boolean replayed) {

        MoneyResponse response = new MoneyResponse(
                transaction.getTransactionId(),
                transaction.getTransactionType(),
                transaction.getAmount(),
                transaction.getTransactionStatus(),
                bankAccount.getBalance(),
                walletAccount.getBalance()
        );

        return new MoneyResult(response, replayed);
    }
}
