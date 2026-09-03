package com.antithesis.springhegel.user;

import static com.antithesis.springhegel.user.EmailPasswordGenerators.DIGITS;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.LOWERCASE;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.SPECIAL;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.UPPERCASE;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.blankStrings;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.controlCharacters;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.invalidEmails;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.invalidPasswords;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.malformedEmails;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.nonAsciiCharacters;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.overlongEmails;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.passwordsWithAllClasses;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.randomizeCase;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.replaceAt;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.replaceClass;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.replacementFor;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.validEmails;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.validPasswords;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RegistrationValidatorPropertyTest {

    private static final String EMAIL_BLANK = "Email must not be blank";
    private static final String EMAIL_TOO_LONG = "Email must not exceed 254 characters";
    private static final String EMAIL_INVALID = "Email must be a valid email address";
    private static final String PASSWORD_BLANK = "Password must not be blank";
    private static final String PASSWORD_LENGTH = "Password must be between 8 and 32 characters";
    private static final String PASSWORD_ASCII = "Password must contain only printable ASCII characters";
    private static final String PASSWORD_CLASSES =
            "Password must contain an uppercase letter, a lowercase letter, a digit and a special character";
    private static final Set<String> EMAIL_MESSAGES = Set.of(EMAIL_BLANK, EMAIL_TOO_LONG, EMAIL_INVALID);
    private static final Set<String> PASSWORD_MESSAGES =
            Set.of(PASSWORD_BLANK, PASSWORD_LENGTH, PASSWORD_ASCII, PASSWORD_CLASSES);

    private final RegistrationValidator validator = new RegistrationValidator();

    @HegelTest
    void validEmailAndPasswordProduceNoErrors(TestCase tc) {
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(validPasswords(), "password");

        assertEquals(List.of(), validator.validate(validator.normalizeEmail(email), password));
    }

    @HegelTest
    void invalidEmailsAreRejectedWithAnEmailMessage(TestCase tc) {
        String email = tc.draw(invalidEmails(), "email");
        String password = tc.draw(validPasswords(), "password");

        List<String> errors = validator.validate(validator.normalizeEmail(email), password);

        assertTrue(errors.stream().anyMatch(EMAIL_MESSAGES::contains), errors.toString());
        assertTrue(errors.stream().noneMatch(PASSWORD_MESSAGES::contains), errors.toString());
    }

    @HegelTest
    void invalidPasswordsAreRejectedWithAPasswordMessage(TestCase tc) {
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(invalidPasswords(), "password");

        List<String> errors = validator.validate(email, password);

        assertTrue(errors.stream().anyMatch(PASSWORD_MESSAGES::contains), errors.toString());
        assertTrue(errors.stream().noneMatch(EMAIL_MESSAGES::contains), errors.toString());
    }

    @HegelTest
    void blankEmailProducesOnlyTheBlankMessage(TestCase tc) {
        String email = tc.draw(blankStrings(), "email");
        String password = tc.draw(validPasswords(), "password");

        assertEquals(List.of(EMAIL_BLANK), validator.validate(validator.normalizeEmail(email), password));
    }

    @HegelTest
    void overlongEmailProducesTheLengthMessage(TestCase tc) {
        String email = tc.draw(overlongEmails(), "email");
        String password = tc.draw(validPasswords(), "password");

        List<String> errors = validator.validate(validator.normalizeEmail(email), password);

        assertTrue(errors.contains(EMAIL_TOO_LONG), errors.toString());
    }

    @HegelTest
    void malformedEmailProducesTheFormatMessage(TestCase tc) {
        String email = tc.draw(malformedEmails(), "email");
        String password = tc.draw(validPasswords(), "password");

        List<String> errors = validator.validate(validator.normalizeEmail(email), password);

        assertEquals(List.of(EMAIL_INVALID), errors);
    }

    @HegelTest
    void normalizeEmailIsIdempotent(TestCase tc) {
        String raw = tc.draw(text().maxSize(40), "raw");

        String once = validator.normalizeEmail(raw);

        assertEquals(once, validator.normalizeEmail(once));
    }

    @HegelTest
    void normalizeEmailStripsWhitespaceAndUppercase(TestCase tc) {
        String raw = tc.draw(text().maxSize(40), "raw");

        String normalized = validator.normalizeEmail(raw);

        assertEquals(normalized, normalized.strip());
        assertTrue(normalized.chars().noneMatch(c -> c >= 'A' && c <= 'Z'), normalized);
    }

    @HegelTest
    void caseVariantsOfAnEmailNormalizeToTheSameValue(TestCase tc) {
        String email = tc.draw(validEmails(), "email");
        String variant = randomizeCase(tc, email);

        assertEquals(validator.normalizeEmail(email), validator.normalizeEmail(variant));
    }

    @HegelTest
    void passwordLengthBoundsAreInclusive(TestCase tc) {
        String email = tc.draw(validEmails(), "email");

        assertEquals(List.of(), validator.validate(email, tc.draw(passwordsWithAllClasses(8), "length8")));
        assertEquals(List.of(), validator.validate(email, tc.draw(passwordsWithAllClasses(32), "length32")));

        for (String outOfBounds : List.of(
                tc.draw(passwordsWithAllClasses(7), "length7"),
                tc.draw(passwordsWithAllClasses(33), "length33"))) {
            List<String> errors = validator.validate(email, outOfBounds);
            assertTrue(errors.contains(PASSWORD_LENGTH), errors.toString());
            assertFalse(errors.contains(PASSWORD_CLASSES), errors.toString());
        }
    }

    @HegelTest
    void passwordMissingAnyRequiredClassIsRejected(TestCase tc) {
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(validPasswords(), "password");

        for (String alphabet : List.of(UPPERCASE, LOWERCASE, DIGITS, SPECIAL)) {
            String withoutClass = replaceClass(password, alphabet, replacementFor(alphabet));
            List<String> errors = validator.validate(email, withoutClass);
            assertEquals(List.of(PASSWORD_CLASSES), errors, "missing class from " + alphabet);
        }
    }

    @HegelTest
    void interiorSpaceInPasswordIsAllowed(TestCase tc) {
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(passwordsWithAllClasses(tc.draw(integers().min(8).max(31), "length")), "password");
        int position = tc.draw(integers().min(1).max(password.length() - 1), "position");
        String withSpace = password.substring(0, position) + ' ' + password.substring(position);

        assertEquals(List.of(), validator.validate(email, withSpace));
    }

    @HegelTest
    void nonPrintableCharactersInPasswordAreRejected(TestCase tc) {
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(validPasswords(), "password");
        int position = tc.draw(integers().min(0).max(password.length() - 1), "position");

        for (char bad : List.of(tc.draw(controlCharacters(), "control"), tc.draw(nonAsciiCharacters(), "nonAscii"))) {
            List<String> errors = validator.validate(email, replaceAt(password, position, bad));
            assertEquals(List.of(PASSWORD_ASCII), errors, "codepoint " + (int) bad);
        }
    }

    @Test
    void normalizeEmailOfNullIsEmpty() {
        assertEquals("", validator.normalizeEmail(null));
    }
}
