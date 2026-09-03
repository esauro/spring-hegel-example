package com.antithesis.springhegel.user;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Pure normalization and validation rules for registration input.
 *
 * <p>Email rule: after normalization the value must be non-blank, at most 254 characters and
 * match {@code ^[a-z0-9._%+-]+@[a-z0-9-]+(\.[a-z0-9-]+)+$}. Password rule: non-blank, 8–32
 * characters, printable ASCII only, containing at least one uppercase letter, one lowercase
 * letter, one digit and one special character from {@code !"#$%&'()*+,-./:;<=>?@[\]^_`{|}~}.
 */
@Component
public class RegistrationValidator {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[a-z0-9._%+-]+@[a-z0-9-]+(\\.[a-z0-9-]+)+$");
    private static final String UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String LOWERCASE = "abcdefghijklmnopqrstuvwxyz";
    private static final String DIGITS = "0123456789";
    private static final String SPECIAL_CHARACTERS = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 32;
    private static final int MIN_PRINTABLE_ASCII = 0x20;
    private static final int MAX_PRINTABLE_ASCII = 0x7E;

    /**
     * Trims and lowercases (ASCII, locale-independent) the raw email; {@code null} becomes "".
     */
    public String normalizeEmail(String rawEmail) {
        if (rawEmail == null) {
            return "";
        }
        return rawEmail.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Validates an already-normalized email and an already-trimmed password, returning every
     * failing rule's message (empty when the input is valid). Never throws.
     */
    public List<String> validate(String normalizedEmail, String trimmedPassword) {
        List<String> errors = new ArrayList<>();
        validateEmail(normalizedEmail, errors);
        validatePassword(trimmedPassword, errors);
        return List.copyOf(errors);
    }

    private static void validateEmail(String email, List<String> errors) {
        if (email.isBlank()) {
            errors.add("Email must not be blank");
            return;
        }
        if (email.length() > MAX_EMAIL_LENGTH) {
            errors.add("Email must not exceed 254 characters");
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            errors.add("Email must be a valid email address");
        }
    }

    private static void validatePassword(String password, List<String> errors) {
        if (password.isBlank()) {
            errors.add("Password must not be blank");
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH || password.length() > MAX_PASSWORD_LENGTH) {
            errors.add("Password must be between 8 and 32 characters");
        }
        if (!isPrintableAscii(password)) {
            errors.add("Password must contain only printable ASCII characters");
            return;
        }
        if (!hasAllCharacterClasses(password)) {
            errors.add("Password must contain an uppercase letter, a lowercase letter, a digit and a special character");
        }
    }

    private static boolean isPrintableAscii(String password) {
        return password.chars().allMatch(c -> c >= MIN_PRINTABLE_ASCII && c <= MAX_PRINTABLE_ASCII);
    }

    private static boolean hasAllCharacterClasses(String password) {
        return containsAny(password, UPPERCASE)
                && containsAny(password, LOWERCASE)
                && containsAny(password, DIGITS)
                && containsAny(password, SPECIAL_CHARACTERS);
    }

    private static boolean containsAny(String password, String alphabet) {
        return password.chars().anyMatch(c -> alphabet.indexOf(c) >= 0);
    }
}
