package com.aureus.aureus.service;

import com.aureus.aureus.dto.PageResponse;
import com.aureus.aureus.dto.TransactionDirection;
import com.aureus.aureus.dto.TransactionView;

import com.aureus.aureus.entity.Account;
import com.aureus.aureus.entity.AccountType;
import com.aureus.aureus.entity.Transaction;
import com.aureus.aureus.entity.TransactionStatus;
import com.aureus.aureus.entity.TransactionType;
import com.aureus.aureus.entity.User;

import com.aureus.aureus.exception.AccountNotFoundException;
import com.aureus.aureus.exception.InvalidRequestException;

import com.aureus.aureus.repository.AccountRepository;
import com.aureus.aureus.repository.TransactionRepository;
import com.aureus.aureus.repository.UserRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class TransactionHistoryServiceImpl
        implements TransactionHistoryService {

    private static final int MAX_PAGE_SIZE = 50;

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    public TransactionHistoryServiceImpl(
            TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            UserRepository userRepository) {

        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
    }


    // =========================================================
    // 1. GET TRANSACTION HISTORY
    // =========================================================

    @Override
    public PageResponse<TransactionView> getTransactionHistory(
            Integer userId,
            int page,
            int size,
            TransactionType type,
            TransactionStatus status,
            LocalDate from,
            LocalDate to) {

        // STEP 1: Validate pagination and date range.

        validatePagination(page, size);
        validateDateRange(from, to);

        // STEP 2: Normalize page size.

        int actualSize = Math.min(size, MAX_PAGE_SIZE);

        // STEP 3: Resolve the current user's BANK and WALLET accounts.

        List<Integer> ownAccountIds =
                getOwnAccountIds(userId);

        Set<Integer> ownAccountIdSet =
                new HashSet<>(ownAccountIds);

        // STEP 4: Convert LocalDate filters into LocalDateTime.

        LocalDateTime fromDateTime =
                from == null
                        ? null
                        : from.atStartOfDay();

        /*
         * The next day's midnight is an exclusive upper bound.
         *
         * Example:
         * to = 2026-11-09
         *
         * toExclusiveDateTime =
         * 2026-11-10T00:00:00
         *
         * Therefore, all transactions on November 9
         * are included.
         */

        LocalDateTime toExclusiveDateTime =
                to == null
                        ? null
                        : to.plusDays(1).atStartOfDay();

        // STEP 5: Configure pagination and stable sorting.

        Pageable pageable = PageRequest.of(
                page,
                actualSize,
                Sort.by(
                        Sort.Order.desc("transactionDateTime"),
                        Sort.Order.desc("transactionId")
                )
        );

        // STEP 6: Retrieve the database page.

        Page<Transaction> transactionPage =
                transactionRepository.findHistoryForAccounts(
                        ownAccountIds,
                        type,
                        status,
                        fromDateTime,
                        toExclusiveDateTime,
                        pageable
                );

        // STEP 7: Resolve counterparty emails in batches.

        Map<Integer, String> counterpartyEmails =
                resolveCounterpartyEmails(
                        transactionPage.getContent(),
                        ownAccountIdSet,
                        userId
                );

        // STEP 8: Convert Transaction entities into TransactionView DTOs.

        List<TransactionView> content =
                transactionPage.getContent()
                        .stream()
                        .map(transaction ->
                                mapToTransactionView(
                                        transaction,
                                        ownAccountIdSet,
                                        counterpartyEmails
                                )
                        )
                        .toList();

        // STEP 9: Return our custom pagination response.

        return new PageResponse<>(
                content,
                page,
                actualSize,
                transactionPage.getTotalElements(),
                transactionPage.getTotalPages()
        );
    }


    // =========================================================
    // 2. GET SINGLE TRANSACTION BY ID
    // =========================================================

    @Override
    public TransactionView getTransactionById(
            Integer userId,
            Integer transactionId) {

        // STEP 1: Resolve the authenticated user's accounts.

        List<Integer> ownAccountIds =
                getOwnAccountIds(userId);

        Set<Integer> ownAccountIdSet =
                new HashSet<>(ownAccountIds);

        // STEP 2: Retrieve the transaction.

        /*
         * We only return the transaction if the current user
         * owns either its sender or receiver account.
         *
         * A transaction belonging exclusively to another user
         * produces the same 404 response as a missing transaction.
         */

        Transaction transaction =
                transactionRepository.findById(transactionId)
                        .filter(t ->
                                ownAccountIdSet.contains(
                                        t.getSenderAccountId()
                                )
                                ||
                                ownAccountIdSet.contains(
                                        t.getReceiverAccountId()
                                )
                        )
                        .orElseThrow(() ->
                                new AccountNotFoundException(
                                        "Transaction not found"
                                )
                        );

        // STEP 3: Resolve counterparty email.

        Map<Integer, String> counterpartyEmails =
                resolveCounterpartyEmails(
                        List.of(transaction),
                        ownAccountIdSet,
                        userId
                );

        // STEP 4: Return the safe DTO.

        return mapToTransactionView(
                transaction,
                ownAccountIdSet,
                counterpartyEmails
        );
    }


    // =========================================================
    // 3. RESOLVE THE USER'S BANK AND WALLET ACCOUNT IDS
    // =========================================================

    private List<Integer> getOwnAccountIds(Integer userId) {

        List<Account> accounts =
                accountRepository.findByUserId(userId);

        Integer bankAccountId = null;
        Integer walletAccountId = null;

        for (Account account : accounts) {

            if (account.getAccountType() == AccountType.BANK) {

                if (bankAccountId != null) {
                    throw new InvalidRequestException(
                            "Multiple BANK accounts found"
                    );
                }

                bankAccountId = account.getAccountId();
            }

            else if (account.getAccountType() == AccountType.WALLET) {

                if (walletAccountId != null) {
                    throw new InvalidRequestException(
                            "Multiple WALLET accounts found"
                    );
                }

                walletAccountId = account.getAccountId();
            }
        }

        if (bankAccountId == null || walletAccountId == null) {
            throw new AccountNotFoundException(
                    "Customer BANK or WALLET account not found"
            );
        }

        return List.of(bankAccountId, walletAccountId);
    }


    // =========================================================
    // 4. PAGINATION VALIDATION
    // =========================================================

    private void validatePagination(int page, int size) {

        if (page < 0) {
            throw new InvalidRequestException(
                    "Page number cannot be negative"
            );
        }

        if (size < 1) {
            throw new InvalidRequestException(
                    "Page size must be at least 1"
            );
        }
    }


    // =========================================================
    // 5. DATE RANGE VALIDATION
    // =========================================================

    private void validateDateRange(
            LocalDate from,
            LocalDate to) {

        if (from != null && to != null && from.isAfter(to)) {

            throw new InvalidRequestException(
                    "The from date must be on or before the to date"
            );
        }
    }


    // =========================================================
    // 6. BATCH RESOLVE COUNTERPARTY EMAILS
    // =========================================================

    private Map<Integer, String> resolveCounterpartyEmails(
            List<Transaction> transactions,
            Set<Integer> ownAccountIds,
            Integer userId) {

        // STEP 1: Collect external account IDs.

        Set<Integer> externalAccountIds = new HashSet<>();

        for (Transaction transaction : transactions) {

            /*
             * Opening balance transactions have SYSTEM as one side.
             * We display "Aureus" instead of resolving SYSTEM details.
             */

            if (transaction.getTransactionType()
                    == TransactionType.OPENING_BALANCE) {
                continue;
            }

            Integer senderAccountId =
                    transaction.getSenderAccountId();

            Integer receiverAccountId =
                    transaction.getReceiverAccountId();

            if (!ownAccountIds.contains(senderAccountId)) {
                externalAccountIds.add(senderAccountId);
            }

            if (!ownAccountIds.contains(receiverAccountId)) {
                externalAccountIds.add(receiverAccountId);
            }
        }

        if (externalAccountIds.isEmpty()) {
            return Map.of();
        }

        // STEP 2: Load all external accounts in one batch.

        List<Account> externalAccounts =
                accountRepository.findAllById(externalAccountIds);

        Set<Integer> counterpartyUserIds = new HashSet<>();

        for (Account account : externalAccounts) {

            /*
             * Never resolve SYSTEM accounts to users.
             * Also avoid treating another account of the current
             * user as an external counterparty.
             */

            if (account.getAccountType() == AccountType.SYSTEM) {
                continue;
            }

            if (account.getUserId() == null
                    || account.getUserId().equals(userId)) {
                continue;
            }

            counterpartyUserIds.add(account.getUserId());
        }

        if (counterpartyUserIds.isEmpty()) {
            return Map.of();
        }

        // STEP 3: Load all counterparty users in one batch.

        List<User> counterpartyUsers =
                userRepository.findAllById(counterpartyUserIds);

        Map<Integer, String> emailByUserId = new HashMap<>();

        for (User user : counterpartyUsers) {
            emailByUserId.put(
                    user.getUserId(),
                    user.getEmail()
            );
        }

        // STEP 4: Map external account IDs to user emails.

        Map<Integer, String> emailByAccountId = new HashMap<>();

        for (Account account : externalAccounts) {

            if (account.getAccountType() == AccountType.SYSTEM) {
                continue;
            }

            if (account.getUserId() == null
                    || account.getUserId().equals(userId)) {
                continue;
            }

            String email =
                    emailByUserId.get(account.getUserId());

            if (email != null) {
                emailByAccountId.put(
                        account.getAccountId(),
                        email
                );
            }
        }

        return emailByAccountId;
    }


    // =========================================================
    // 7. MAP TRANSACTION ENTITY TO TRANSACTION VIEW
    // =========================================================

    private TransactionView mapToTransactionView(
            Transaction transaction,
            Set<Integer> ownAccountIds,
            Map<Integer, String> counterpartyEmails) {

        TransactionDirection direction =
                determineDirection(transaction, ownAccountIds);

        String counterparty =
                determineCounterparty(
                        transaction,
                        direction,
                        counterpartyEmails
                );

        return new TransactionView(
                transaction.getTransactionId(),
                transaction.getTransactionType(),
                direction,
                transaction.getAmount(),
                transaction.getTransactionStatus(),
                counterparty,
                transaction.getTransactionDateTime()
        );
    }


    // =========================================================
    // 8. DETERMINE TRANSACTION DIRECTION
    // =========================================================

    private TransactionDirection determineDirection(
            Transaction transaction,
            Set<Integer> ownAccountIds) {

        Integer senderAccountId =
                transaction.getSenderAccountId();

        Integer receiverAccountId =
                transaction.getReceiverAccountId();

        boolean currentUserIsSender =
                ownAccountIds.contains(senderAccountId);

        boolean currentUserIsReceiver =
                ownAccountIds.contains(receiverAccountId);

        return switch (transaction.getTransactionType()) {

            case OPENING_BALANCE ->
                    TransactionDirection.OPENING_BALANCE;

            case ADD_MONEY ->
                    TransactionDirection.ADDED_TO_WALLET;

            case WITHDRAWAL ->
                    TransactionDirection.TO_BANK;

            case TRANSFER -> {

                if (currentUserIsSender) {
                    yield TransactionDirection.SENT;
                }

                if (currentUserIsReceiver) {
                    yield TransactionDirection.RECEIVED;
                }

                throw new AccountNotFoundException(
                        "Transaction not found"
                );
            }

            case REVERSAL -> {

                if (currentUserIsSender) {
                    yield TransactionDirection.REVERSAL_DEBIT;
                }

                if (currentUserIsReceiver) {
                    yield TransactionDirection.REVERSAL_CREDIT;
                }

                throw new AccountNotFoundException(
                        "Transaction not found"
                );
            }
        };
    }


    // =========================================================
    // 9. DETERMINE COUNTERPARTY
    // =========================================================

    private String determineCounterparty(
            Transaction transaction,
            TransactionDirection direction,
            Map<Integer, String> counterpartyEmails) {

        // Opening balance always displays Aureus.

        if (transaction.getTransactionType()
                == TransactionType.OPENING_BALANCE) {
            return "Aureus";
        }

        /*
         * For outgoing transactions, the counterparty is the receiver.
         * For incoming transactions, the counterparty is the sender.
         *
         * Add-money and withdrawal involve the user's own BANK
         * and WALLET accounts, so their counterparty remains null.
         */

        Integer counterpartyAccountId = switch (direction) {

            case SENT, REVERSAL_DEBIT ->
                    transaction.getReceiverAccountId();

            case RECEIVED, REVERSAL_CREDIT ->
                    transaction.getSenderAccountId();

            default -> null;
        };

        if (counterpartyAccountId == null) {
            return null;
        }

        return counterpartyEmails.get(counterpartyAccountId);
    }
}