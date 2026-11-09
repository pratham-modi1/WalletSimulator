package com.aureus.aureus.controller;

import com.aureus.aureus.dto.MyAccountsResponse;

import com.aureus.aureus.security.AuthenticatedUser;
import com.aureus.aureus.service.AccountService;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }



    @GetMapping("/me")
    public ResponseEntity<MyAccountsResponse> getMyAccounts(
            @AuthenticationPrincipal AuthenticatedUser principal) {

        MyAccountsResponse response =
                accountService.getMyAccounts(principal.userId());

        return ResponseEntity.ok(response);
    }
    
}