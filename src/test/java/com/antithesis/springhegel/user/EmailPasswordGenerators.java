package com.antithesis.springhegel.user;

import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.composite;
import static dev.hegel.Generators.fromRegex;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.oneOf;
import static dev.hegel.Generators.sampledFrom;

import dev.hegel.Generator;
import dev.hegel.TestCase;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/** Hegel generators and helpers shared by the registration and login property tests. */
public final class EmailPasswordGenerators {

    static final String UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    static final String LOWERCASE = "abcdefghijklmnopqrstuvwxyz";
    static final String DIGITS = "0123456789";
    static final String SPECIAL = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~";
    /** Printable ASCII without the space, so trimming never alters a generated password. */
    static final String FILLER = IntStream.rangeClosed(0x21, 0x7E)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
            .toString();

    private EmailPasswordGenerators() {
    }

    /** Strings with the exact shape of a real session token (43 Base64-URL characters) but never issued. */
    static Generator<String> tokensLike() {
        return fromRegex("[A-Za-z0-9_-]{43}").fullmatch(true);
    }

    public static Generator<String> validEmails() {
        return fromRegex("[a-z0-9._%+-]{1,20}@[a-z0-9-]{1,15}(\\.[a-z0-9-]{1,10}){1,3}").fullmatch(true);
    }

    static Generator<String> blankStrings() {
        return lists(sampledFrom(" ", "\t", "\n")).maxSize(4).map(parts -> String.join("", parts));
    }

    static Generator<String> malformedEmails() {
        return oneOf(
                fromRegex("[a-z0-9.]{1,30}").fullmatch(true),
                fromRegex("@[a-z]{1,10}\\.[a-z]{2,5}").fullmatch(true),
                fromRegex("[a-z]{1,10}@[a-z]{1,10}").fullmatch(true),
                fromRegex("[a-z]{1,5}[ ,;!/][a-z]{1,5}@[a-z]{1,5}\\.[a-z]{2,3}").fullmatch(true));
    }

    static Generator<String> overlongEmails() {
        return fromRegex("[a-z]{250,260}@[a-z]{5}\\.[a-z]{3}").fullmatch(true);
    }

    static Generator<String> invalidEmails() {
        return oneOf(blankStrings(), malformedEmails(), overlongEmails());
    }

    static Generator<Character> charFrom(String alphabet) {
        return sampledFrom(alphabet.chars().mapToObj(c -> (char) c).toList());
    }

    /** A password of exactly {@code length} (>= 4) characters containing all four required classes. */
    static Generator<String> passwordsWithAllClasses(int length) {
        return composite(tc -> {
            List<Character> pool = new ArrayList<>();
            pool.add(tc.draw(charFrom(UPPERCASE), "upper"));
            pool.add(tc.draw(charFrom(LOWERCASE), "lower"));
            pool.add(tc.draw(charFrom(DIGITS), "digit"));
            pool.add(tc.draw(charFrom(SPECIAL), "special"));
            while (pool.size() < length) {
                pool.add(tc.draw(charFrom(FILLER), "filler"));
            }
            StringBuilder password = new StringBuilder();
            while (!pool.isEmpty()) {
                int index = tc.draw(integers().min(0).max(pool.size() - 1), "position");
                password.append(pool.remove(index));
            }
            return password.toString();
        });
    }

    public static Generator<String> validPasswords() {
        return integers().min(8).max(32).flatMap(EmailPasswordGenerators::passwordsWithAllClasses);
    }

    static Generator<String> tooShortPasswords() {
        return integers().min(4).max(7).flatMap(EmailPasswordGenerators::passwordsWithAllClasses);
    }

    static Generator<String> tooLongPasswords() {
        return integers().min(33).max(40).flatMap(EmailPasswordGenerators::passwordsWithAllClasses);
    }

    static Generator<String> passwordsMissingOneClass() {
        return validPasswords().flatMap(password -> sampledFrom(UPPERCASE, LOWERCASE, DIGITS, SPECIAL)
                .map(alphabet -> replaceClass(password, alphabet, replacementFor(alphabet))));
    }

    static Generator<Character> controlCharacters() {
        return integers().min(0x00).max(0x1F).map(i -> (char) i.intValue());
    }

    static Generator<Character> nonAsciiCharacters() {
        return integers().min(0x7F).max(0xD7FF).map(i -> (char) i.intValue());
    }

    static Generator<String> passwordsWithNonPrintableCharacter() {
        return composite(tc -> {
            String password = tc.draw(validPasswords(), "password");
            char bad = tc.draw(oneOf(controlCharacters(), nonAsciiCharacters()), "bad");
            int position = tc.draw(integers().min(0).max(password.length() - 1), "position");
            return replaceAt(password, position, bad);
        });
    }

    static Generator<String> invalidPasswords() {
        return oneOf(
                blankStrings(),
                tooShortPasswords(),
                tooLongPasswords(),
                passwordsMissingOneClass(),
                passwordsWithNonPrintableCharacter());
    }

    /** Swaps every character of {@code alphabet} for {@code replacement}, preserving length. */
    static String replaceClass(String password, String alphabet, char replacement) {
        StringBuilder result = new StringBuilder(password.length());
        for (char c : password.toCharArray()) {
            result.append(alphabet.indexOf(c) >= 0 ? replacement : c);
        }
        return result.toString();
    }

    /** A replacement character that belongs to a different class than {@code alphabet}. */
    static char replacementFor(String alphabet) {
        return alphabet.equals(LOWERCASE) ? 'A' : 'a';
    }

    static String replaceAt(String text, int position, char replacement) {
        return text.substring(0, position) + replacement + text.substring(position + 1);
    }

    /** Upper- or lowercases each character according to a fresh draw. */
    public static String randomizeCase(TestCase tc, String text) {
        StringBuilder result = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            boolean upper = tc.draw(booleans(), "upper");
            result.append(upper ? Character.toUpperCase(c) : Character.toLowerCase(c));
        }
        return result.toString();
    }
}
