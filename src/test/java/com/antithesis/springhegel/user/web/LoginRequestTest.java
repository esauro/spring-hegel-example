package com.antithesis.springhegel.user.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LoginRequestTest {

    @Test
    void toStringRedactsThePassword() {
        LoginRequest request = new LoginRequest("alice@example.com", "Str0ng!pass");

        String rendered = request.toString();

        assertTrue(rendered.contains("alice@example.com"), rendered);
        assertTrue(rendered.contains("REDACTED"), rendered);
        assertFalse(rendered.contains("Str0ng!pass"), rendered);
    }
}
