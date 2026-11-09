
package com.aureus.aureus.service;

import com.aureus.aureus.dto.LoginRequest;
import com.aureus.aureus.dto.LoginResponse;
import com.aureus.aureus.dto.RegisterRequest;
import com.aureus.aureus.dto.RegisterResponse;

public interface AuthService {

    RegisterResponse register(RegisterRequest request);

    LoginResponse login(LoginRequest request);
}
// Takes registration data (email and password) as a RegisterRequest object
// and returns a RegisterResponse object containing the user's ID, email,
// bank balance, and wallet balance.
// This is only a method declaration; the actual registration logic
// will be written in the implementing class.
