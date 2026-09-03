package com.antithesis.springhegel.user.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RegistrationRequestTest {

    @Test
    void toStringRedactsThePassword() {
        RegistrationRequest request = new RegistrationRequest("alice@example.com", "Str0ng!pass");

        String rendered = request.toString();

        assertTrue(rendered.contains("alice@example.com"), rendered);
        assertTrue(rendered.contains("REDACTED"), rendered);
        assertFalse(rendered.contains("Str0ng!pass"), rendered);
    }
}
