package com.aureus.aureus.service;

import com.aureus.aureus.dto.LoginRequest;
import com.aureus.aureus.dto.LoginResponse;
import com.aureus.aureus.dto.RegisterRequest;
import com.aureus.aureus.dto.RegisterResponse;

import com.aureus.aureus.entity.Account;
import com.aureus.aureus.entity.AccountType;
import com.aureus.aureus.entity.EntryType;
import com.aureus.aureus.entity.LedgerEntry;
import com.aureus.aureus.entity.Role;
import com.aureus.aureus.entity.Transaction;
import com.aureus.aureus.entity.TransactionStatus;
import com.aureus.aureus.entity.TransactionType;
import com.aureus.aureus.entity.User;
import com.aureus.aureus.entity.UserStatus;

import com.aureus.aureus.exception.DuplicateEmailException;

import com.aureus.aureus.repository.AccountRepository;
import com.aureus.aureus.repository.LedgerEntryRepository;
import com.aureus.aureus.repository.TransactionRepository;
import com.aureus.aureus.repository.UserRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

@Service
public class AuthServiceImpl implements AuthService {

    private static final BigDecimal OPENING_BALANCE =
            new BigDecimal("5000.00");

    private static final BigDecimal ZERO =
            new BigDecimal("0.00");

    private final UserRepository userRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthServiceImpl(
            UserRepository userRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            LedgerEntryRepository ledgerEntryRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {

        this.userRepository = userRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;

    }

    @Override
    @Transactional
    public RegisterResponse register(RegisterRequest request) {

        // STEP 1: Normalize email and reject existing users.
        String email = request.getEmail()
                .trim()
                .toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmail(email)) {
            throw new DuplicateEmailException(
                    "Email is already registered"
            );
        }

        // STEP 2: Hash password and insert the user.
        String passwordHash =
                passwordEncoder.encode(request.getPassword());

        User user = new User(
                email,
                passwordHash,
                Role.USER,
                UserStatus.ACTIVE
        );

        try {
            // Flush now so MySQL checks uniqueness and generates userId.
            user = userRepository.saveAndFlush(user);

        } catch (DataIntegrityViolationException exception) {
            // Covers the race where concurrent requests insert
            // the same email after both passed existsByEmail().
            throw new DuplicateEmailException(
                    "Email is already registered",
                    exception
            );
        }

        Integer userId = user.getUserId();

        // STEP 3: Create the customer's BANK and WALLET accounts.
        Account bankAccount = accountRepository.saveAndFlush(
                new Account(
                        userId,
                        AccountType.BANK,
                        OPENING_BALANCE
                )
        );

        Account walletAccount = accountRepository.saveAndFlush(
                new Account(
                        userId,
                        AccountType.WALLET,
                        ZERO
                )
        );

        // STEP 4: Find the SYSTEM account and debit it safely.
        List<Account> systemAccounts =
                accountRepository.findByAccountType(AccountType.SYSTEM);

        // Our current schema does not enforce a single SYSTEM account
        // globally, so verify the expected seed-data invariant.
        if (systemAccounts.size() != 1) {
            throw new IllegalStateException(
                    "Expected exactly one SYSTEM account"
            );
        }

        Integer systemAccountId =
                systemAccounts.get(0).getAccountId();

        int rowsUpdated =
                accountRepository.debitIfSufficientBalance(
                        systemAccountId,
                        OPENING_BALANCE
                );

        if (rowsUpdated != 1) {
            throw new IllegalStateException(
                    "SYSTEM account has insufficient funds "
                            + "or could not be debited"
            );
        }

        // STEP 5: Record the opening-balance transaction.
        String idempotencyKey = "OPENING-" + userId;

        Transaction openingTransaction =
                new Transaction(
                        systemAccountId,
                        bankAccount.getAccountId(),
                        OPENING_BALANCE,
                        TransactionType.OPENING_BALANCE,
                        TransactionStatus.COMPLETED,
                        idempotencyKey,
                        null
                );

        openingTransaction =
                transactionRepository.saveAndFlush(openingTransaction);

        Integer transactionId =
                openingTransaction.getTransactionId();

        // STEP 6: Record the matching debit and credit entries.
        LedgerEntry debitEntry = new LedgerEntry(
                transactionId,
                systemAccountId,
                EntryType.DEBIT,
                OPENING_BALANCE
        );

        LedgerEntry creditEntry = new LedgerEntry(
                transactionId,
                bankAccount.getAccountId(),
                EntryType.CREDIT,
                OPENING_BALANCE
        );

        ledgerEntryRepository.saveAll(
                List.of(debitEntry, creditEntry)
        );

        // STEP 7: Return the registration response.
        return new RegisterResponse(
                userId,
                user.getEmail(),
                bankAccount.getBalance(),
                walletAccount.getBalance()
        );
    }


        @Override
        public LoginResponse login(LoginRequest request) {

        String email = request.getEmail()
                .trim()
                .toLowerCase(Locale.ROOT);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() ->
                        new ResponseStatusException(
                                HttpStatus.UNAUTHORIZED,
                                "Invalid email or password"
                        )
                );

        if (user.getUserStatus() != UserStatus.ACTIVE) {
                throw new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Invalid email or password"
                );
        }

        if (!passwordEncoder.matches(
                request.getPassword(),
                user.getPasswordHash())) {

                throw new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Invalid email or password"
                );
        }

        String token = jwtService.generateToken(
                user.getUserId(),
                user.getRole()
        );

        return new LoginResponse(
                token,
                "Bearer",
                jwtService.getExpirationSeconds()
        );

        }
}