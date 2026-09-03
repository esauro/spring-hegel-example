package com.antithesis.springhegel.user.web;

public record RegistrationRequest(String email, String password) {

    @Override
    public String toString() {
        // The default record toString would leak the plaintext password into logs.
        return "RegistrationRequest[email=" + email + ", password=REDACTED]";
    }
}
