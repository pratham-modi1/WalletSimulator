package com.aureus.aureus.controller;

import com.aureus.aureus.dto.PageResponse;
import com.aureus.aureus.dto.TransactionView;

import com.aureus.aureus.entity.TransactionType;
import com.aureus.aureus.entity.TransactionStatus;

import com.aureus.aureus.security.AuthenticatedUser;

import com.aureus.aureus.service.TransactionHistoryService;

import org.springframework.format.annotation.DateTimeFormat;

import org.springframework.http.ResponseEntity;

import org.springframework.security.core.annotation.AuthenticationPrincipal;

import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionHistoryService transactionHistoryService;


    public TransactionController(
            TransactionHistoryService transactionHistoryService) {

        this.transactionHistoryService = transactionHistoryService;
    }


    // GET /api/transactions
    // Retrieve paginated transaction history.

    @GetMapping
    public ResponseEntity<PageResponse<TransactionView>>
    getTransactionHistory(

            @AuthenticationPrincipal AuthenticatedUser principal,

            @RequestParam(defaultValue = "0")
            int page,

            @RequestParam(defaultValue = "20")
            int size,

            @RequestParam(required = false)
            TransactionType type,

            @RequestParam(required = false)
            TransactionStatus status,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,

            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {


        PageResponse<TransactionView> response =
                transactionHistoryService.getTransactionHistory(

                        principal.userId(),

                        page,
                        size,
                        type,
                        status,
                        from,
                        to
                );


        return ResponseEntity.ok(response);
    }



    // GET /api/transactions/{id}
    // Retrieve one transaction.

    @GetMapping("/{id}")
    public ResponseEntity<TransactionView> getTransactionById(

            @AuthenticationPrincipal AuthenticatedUser principal,

            @PathVariable("id")
            Integer transactionId) {


        TransactionView response =
                transactionHistoryService.getTransactionById(

                        principal.userId(),
                        transactionId
                );


        return ResponseEntity.ok(response);
    }

}