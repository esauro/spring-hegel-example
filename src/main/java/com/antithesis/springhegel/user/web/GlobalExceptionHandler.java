package com.antithesis.springhegel.user.web;

import com.antithesis.springhegel.user.EmailAlreadyRegisteredException;
import com.antithesis.springhegel.user.InvalidRegistrationException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    static final String EMAIL_ALREADY_REGISTERED = "EMAIL_ALREADY_REGISTERED";

    @ExceptionHandler(InvalidRegistrationException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRegistration(InvalidRegistrationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(VALIDATION_ERROR, ex.getErrors()));
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ErrorResponse> handleEmailAlreadyRegistered(EmailAlreadyRegisteredException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(EMAIL_ALREADY_REGISTERED, List.of("Email is already registered")));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(VALIDATION_ERROR, List.of("Request body is missing or malformed")));
    }
}
