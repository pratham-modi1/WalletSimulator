package com.aureus.aureus.service;

import com.aureus.aureus.dto.MyAccountsResponse;

public interface AccountService {

    MyAccountsResponse getMyAccounts(Integer userId);
}