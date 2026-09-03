package com.antithesis.springhegel.user;

/**
 * Thrown when a login attempt does not identify a registered user: either the email is unknown or
 * the password does not match. The two causes are intentionally indistinguishable so the login
 * endpoint cannot be used to discover which emails are registered. Carries no user input.
 */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
