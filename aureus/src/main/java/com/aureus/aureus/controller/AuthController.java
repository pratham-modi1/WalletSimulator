package com.aureus.aureus.controller;
import com.aureus.aureus.dto.RegisterRequest;
import com.aureus.aureus.dto.RegisterResponse;
import com.aureus.aureus.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<RegisterResponse> register(
            @Valid @RequestBody RegisterRequest request) {

        RegisterResponse response = authService.register(request);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}



// 1. Makes this class handle HTTP requests and return response data, usually as JSON.
//@RestController

// 2. Sets the base URL for all endpoints in this controller.
//@RequestMapping("/api/auth")

// 3. Maps POST /api/auth/register to this method.
//@PostMapping("/register")

// 4. Receives the AuthService bean through constructor injection.
//AuthService authService;

// 5. Converts the incoming JSON request body into a RegisterRequest object.
//@RequestBody

// 6. Triggers validation rules defined in RegisterRequest, such as @Email and @Size.
//@Valid

// 7. Returns HTTP 201 Created along with the response object as JSON.
//ResponseEntity.status(HttpStatus.CREATED).body(response);
