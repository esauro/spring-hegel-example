package com.antithesis.springhegel.user;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/** Produces unguessable, URL-safe session tokens. */
@Component
public class SessionTokenGenerator {

    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random;

    public SessionTokenGenerator() {
        this.random = new SecureRandom();
    }

    /**
     * Returns a fresh token: 32 random bytes, Base64 URL-safe encoded without padding, which is
     * always 43 characters from the alphabet {@code [A-Za-z0-9_-]}.
     */
    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
