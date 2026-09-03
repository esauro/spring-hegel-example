package com.antithesis.springhegel.user;

import java.util.List;

/**
 * Thrown when a registration request violates one or more validation rules.
 * Carries every failing rule's message so the caller can report all of them at once.
 */
public class InvalidRegistrationException extends RuntimeException {

    private final List<String> errors;

    public InvalidRegistrationException(List<String> errors) {
        super("Invalid registration request");
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
