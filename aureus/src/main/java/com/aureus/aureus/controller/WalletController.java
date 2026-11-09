
package com.aureus.aureus.controller;

import com.aureus.aureus.dto.MoneyRequest;
import com.aureus.aureus.dto.MoneyResult;
import com.aureus.aureus.dto.MoneyResponse;
import com.aureus.aureus.dto.SendMoneyRequest;
import com.aureus.aureus.dto.SendMoneyResponse;
import com.aureus.aureus.dto.SendMoneyResult;

import com.aureus.aureus.exception.InvalidRequestException;
import com.aureus.aureus.security.AuthenticatedUser;
import com.aureus.aureus.service.WalletService;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/wallet")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping("/add-money")
    public ResponseEntity<MoneyResponse> addMoney(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody MoneyRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String clientKey) {

        validateClientKey(clientKey);

        MoneyResult result = walletService.addMoney(
                authenticatedUser.userId(),
                request.getAmount(),
                clientKey
        );

        HttpStatus status = result.isReplayed()
                ? HttpStatus.OK
                : HttpStatus.CREATED;

        return ResponseEntity.status(status).body(result.getResponse());
    }

    @PostMapping("/transfer-to-bank")
    public ResponseEntity<MoneyResponse> transferToBank(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody MoneyRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String clientKey) {

        validateClientKey(clientKey);

        MoneyResult result = walletService.transferToBank(
                authenticatedUser.userId(),
                request.getAmount(),
                clientKey
        );

        HttpStatus status = result.isReplayed()
                ? HttpStatus.OK
                : HttpStatus.CREATED;

        return ResponseEntity.status(status).body(result.getResponse());
    }

    @PostMapping("/send")
    public ResponseEntity<SendMoneyResponse> sendMoney(
            @AuthenticationPrincipal AuthenticatedUser authenticatedUser,
            @Valid @RequestBody SendMoneyRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String clientKey) {

        validateClientKey(clientKey);

        SendMoneyResult result = walletService.sendMoney(
                authenticatedUser.userId(),
                request.getRecipientEmail(),
                request.getAmount(),
                clientKey
        );

        HttpStatus status = result.isReplayed()
                ? HttpStatus.OK
                : HttpStatus.CREATED;

        return ResponseEntity.status(status).body(result.getResponse());
    }

    private void validateClientKey(String clientKey) {

        if (clientKey == null || clientKey.isBlank()) {
            throw new InvalidRequestException(
                    "Idempotency-Key header is required"
            );
        }

        if (clientKey.length() > 64) {
            throw new InvalidRequestException(
                    "Idempotency-Key cannot exceed 64 characters"
            );
        }
    }
}
