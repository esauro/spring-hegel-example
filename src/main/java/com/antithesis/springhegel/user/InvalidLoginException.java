package com.antithesis.springhegel.user;

import java.util.List;

/**
 * Thrown when a login request is missing its email and/or password (blank after normalization).
 * Carries every failing rule's message so the caller can report all of them at once.
 */
public class InvalidLoginException extends RuntimeException {

    private final List<String> errors;

    public InvalidLoginException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = List.copyOf(errors);
    }

    public List<String> getErrors() {
        return errors;
    }
}
