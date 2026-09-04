package com.antithesis.springhegel.user.web;

import com.antithesis.springhegel.user.EmailAlreadyRegisteredException;
import com.antithesis.springhegel.user.InvalidCredentialsException;
import com.antithesis.springhegel.user.InvalidLoginException;
import com.antithesis.springhegel.user.InvalidRegistrationException;
import com.antithesis.springhegel.user.NotLoggedInException;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    static final String EMAIL_ALREADY_REGISTERED = "EMAIL_ALREADY_REGISTERED";
    static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";
    static final String NOT_LOGGED_IN = "NOT_LOGGED_IN";

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

    @ExceptionHandler(InvalidLoginException.class)
    public ResponseEntity<ErrorResponse> handleInvalidLogin(InvalidLoginException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(VALIDATION_ERROR, ex.getErrors()));
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse(INVALID_CREDENTIALS, List.of("Invalid email or password")));
    }

    @ExceptionHandler(NotLoggedInException.class)
    public ResponseEntity<ErrorResponse> handleNotLoggedIn(NotLoggedInException ex) {
        // A token that fails the live-session check can never become valid again, so the
        // browser is told to drop it.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, SessionController.clearedSessionCookie())
                .body(new ErrorResponse(NOT_LOGGED_IN, List.of("Not logged in")));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(VALIDATION_ERROR, List.of("Request body is missing or malformed")));
    }
}
