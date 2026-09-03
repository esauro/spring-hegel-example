package com.antithesis.springhegel.user.web;

import java.util.List;

/** Unified error payload. {@code code} is one of {@code VALIDATION_ERROR}, {@code EMAIL_ALREADY_REGISTERED}. */
public record ErrorResponse(String code, List<String> messages) {
}
