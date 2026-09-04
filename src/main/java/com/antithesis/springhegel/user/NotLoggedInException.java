package com.antithesis.springhegel.user;

/**
 * Thrown when an action that requires a logged-in user is attempted with a token that does not
 * resolve to a live session: {@code null}, unknown, already ended, or expired. Unlike
 * {@link UserService#logout}, which silently ignores such tokens, the action is <em>refused</em>.
 * Deliberately carries neither the token nor the reason it failed to resolve.
 */
public class NotLoggedInException extends RuntimeException {

    public NotLoggedInException() {
        super("Not logged in");
    }
}
