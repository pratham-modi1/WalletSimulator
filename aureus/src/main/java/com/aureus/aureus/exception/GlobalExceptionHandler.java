package com.aureus.aureus.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log =
            LoggerFactory.getLogger(GlobalExceptionHandler.class);


    // 1. Handles validation failures such as @Email, @NotBlank,
    //    and @Size(min = 8) in RegisterRequest.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidationErrors(
            MethodArgumentNotValidException ex) {

        Map<String, String> errors = new HashMap<>();

        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.putIfAbsent(
                        error.getField(),
                        error.getDefaultMessage()
                )
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(errors);
    }


    // 2. Handles duplicate registration attempts.
    //    DuplicateEmailException is thrown by AuthServiceImpl
    //    when an email is already registered.
    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<Map<String, String>> handleDuplicateEmail(
            DuplicateEmailException ex) {

        Map<String, String> errors = new HashMap<>();

        errors.put("error", "EMAIL_ALREADY_EXISTS");
        errors.put("message", ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(errors);
    }


    // 3. Handles missing or malformed JSON request bodies.
    //    For example, invalid JSON syntax in a Postman request.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleInvalidRequestBody(
            HttpMessageNotReadableException ex) {

        Map<String, String> errors = new HashMap<>();

        errors.put("error", "INVALID_REQUEST_BODY");
        errors.put(
                "message",
                "Request body is missing or contains invalid JSON"
        );

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(errors);
    }


    // Handles exceptions carrying an explicit HTTP status,
// such as invalid login credentials.
@ExceptionHandler(ResponseStatusException.class)
public ResponseEntity<Map<String, String>> handleResponseStatusException(
        ResponseStatusException ex) {

    Map<String, String> errors = new HashMap<>();

    errors.put(
            "error",
            ex.getStatusCode().toString()
    );

    errors.put(
            "message",
            ex.getReason() != null
                    ? ex.getReason()
                    : "Request failed"
    );

    return ResponseEntity
            .status(ex.getStatusCode())
            .body(errors);
}


    // 4. Handles unexpected exceptions that do not have
    //    a more specific handler in this class.
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleUnexpectedError(
            Exception ex) {

        // Record the actual exception and stack trace in server logs
        // for debugging, without exposing internal details to the client.
        log.error("Unexpected error while processing request", ex);

        Map<String, String> errors = new HashMap<>();

        errors.put("error", "INTERNAL_SERVER_ERROR");
        errors.put("message", "An unexpected error occurred");

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errors);
    }

    @ExceptionHandler(InsufficientFundsException.class)
public ResponseEntity<Map<String, String>> handleInsufficientFunds(
        InsufficientFundsException ex) {

    return ResponseEntity
            .status(HttpStatus.UNPROCESSABLE_ENTITY)
            .body(Map.of(
                    "error", "Insufficient Funds",
                    "message", ex.getMessage()
            ));
}


@ExceptionHandler(IdempotencyConflictException.class)
public ResponseEntity<Map<String, String>> handleIdempotencyConflict(
        IdempotencyConflictException ex) {

    return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(Map.of(
                    "error", "Idempotency Conflict",
                    "message", ex.getMessage()
            ));
}


@ExceptionHandler(AccountNotFoundException.class)
public ResponseEntity<Map<String, String>> handleAccountNotFound(
        AccountNotFoundException ex) {

    return ResponseEntity
            .status(HttpStatus.NOT_FOUND)
            .body(Map.of(
                    "error", "Account Not Found",
                    "message", ex.getMessage()
            ));
}


@ExceptionHandler(InvalidRequestException.class)
public ResponseEntity<Map<String, String>> handleInvalidRequest(
        InvalidRequestException ex) {

    return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(Map.of(
                    "error", "Invalid Request",
                    "message", ex.getMessage()
            ));
}


@ExceptionHandler(MethodArgumentTypeMismatchException.class)
public ResponseEntity<Map<String, String>> handleInvalidParameter(
        MethodArgumentTypeMismatchException ex) {

    return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(Map.of(
                    "error", "INVALID_PARAMETER",
                    "message", "Invalid value for parameter '"
                            + ex.getName() + "'"
            ));
}
}


// EXPLANATION:
//
// 1. @RestControllerAdvice allows this class to handle exceptions thrown
//    during Spring MVC request processing across the application.
//
// 2. @ExceptionHandler specifies which exception type a method handles.
//    Spring invokes the matching handler when that exception propagates
//    through MVC request processing.
//
// 3. MethodArgumentNotValidException occurs when request validation fails.
//    getBindingResult().getFieldErrors() retrieves field-specific errors.
//    putIfAbsent() keeps the first error message for each field.
//    HTTP 400 means Bad Request.
//
// 4. DuplicateEmailException represents a business-rule violation.
//    AuthServiceImpl throws it when the normalized email already exists
//    or when a concurrent registration loses the unique-email race.
//    HTTP 409 means Conflict.
//
// 5. HttpMessageNotReadableException handles request-body conversion
//    failures, such as malformed JSON. HTTP 400 indicates that the
//    supplied request body could not be read correctly.
//
// 6. The generic Exception handler catches unexpected exceptions that
//    reach MVC and are not handled by a more specific handler.
//    HTTP 500 indicates an unexpected server-side failure.
//    The logger records the full exception for debugging, while the
//    response avoids exposing stack traces or internal implementation.
//
// 7. Each handler returns a ResponseEntity containing an HTTP status
//    and a response body. Spring serializes the Map into JSON.
//
// 8. This class does not perform validation, database operations,
//    password hashing, or transaction rollback itself. Those belong
//    to the relevant validation, service, and transaction mechanisms.