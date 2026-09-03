package com.antithesis.springhegel.user;

import java.time.Instant;

/**
 * Result of a successful login: the token the web layer must place in the cookie, when it expires,
 * and the user it belongs to. Only {@code user} may ever be serialized to a response body.
 */
public record ActiveSession(String token, Instant expiresAt, RegisteredUser user) {

    @Override
    public String toString() {
        // token intentionally redacted so this record can never leak it through logging
        return "ActiveSession[expiresAt=" + expiresAt + ", user=" + user + "]";
    }
}
