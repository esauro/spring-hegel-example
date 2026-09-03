package com.antithesis.springhegel.user;

/**
 * Thrown when the (normalized) email of a registration request is already taken.
 * Deliberately does not carry the email, so user input is never reflected into error payloads.
 */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        super("Email is already registered");
    }
}
