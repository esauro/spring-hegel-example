package com.antithesis.springhegel.user.web;

public record LoginRequest(String email, String password) {

    @Override
    public String toString() {
        // The default record toString would leak the plaintext password into logs.
        return "LoginRequest[email=" + email + ", password=REDACTED]";
    }
}
