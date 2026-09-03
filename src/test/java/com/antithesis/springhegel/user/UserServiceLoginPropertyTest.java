package com.antithesis.springhegel.user;

import static com.antithesis.springhegel.user.EmailPasswordGenerators.blankStrings;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.randomizeCase;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.tokensLike;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.validEmails;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.validPasswords;
import static com.antithesis.springhegel.user.UserServiceImpl.EMAIL_BLANK;
import static com.antithesis.springhegel.user.UserServiceImpl.PASSWORD_BLANK;
import static com.antithesis.springhegel.user.UserServiceImpl.SESSION_LIFETIME;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.oneOf;
import static dev.hegel.Generators.sampledFrom;
import static dev.hegel.Generators.text;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class UserServiceLoginPropertyTest {

    private static final String INVALID_CREDENTIALS = "Invalid email or password";
    private static final Pattern TOKEN_SHAPE = Pattern.compile("[A-Za-z0-9_-]{43}");

    private enum Action { LOGIN, LOGOUT_LIVE, LOGOUT_STALE, QUERY, ADVANCE_PAST_EXPIRY }

    @HegelTest
    void registeredUserCanLogInWithNormalizationVariants(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(validPasswords(), "password");
        RegisteredUser registered = fixture.service().register(email, password);

        ActiveSession session = fixture.service().login(pad(tc, randomizeCase(tc, email)), pad(tc, password), null);

        assertEquals(registered, session.user());
        assertEquals(fixture.clock().instant().plus(SESSION_LIFETIME), session.expiresAt());
        assertTrue(TOKEN_SHAPE.matcher(session.token()).matches(), session.token());
        assertFalse(session.toString().contains(session.token()), session.toString());
        assertEquals(Optional.of(registered), fixture.service().currentUser(session.token()));
        assertEquals(1, fixture.sessions().size());
    }

    @HegelTest
    void wrongPasswordIsRejectedWithTheUniformFailure(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(validPasswords(), "password");
        String other = tc.draw(validPasswords().filter(candidate -> !candidate.equals(password)), "other");
        fixture.service().register(email, password);

        InvalidCredentialsException ex = assertThrows(InvalidCredentialsException.class,
                () -> fixture.service().login(email, other, null));

        assertEquals(INVALID_CREDENTIALS, ex.getMessage());
        assertEquals(0, fixture.sessions().size());
    }

    @HegelTest
    void unknownEmailIsRejectedWithTheUniformFailure(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String registeredEmail = tc.draw(validEmails(), "registeredEmail");
        String unknownEmail = tc.draw(validEmails().filter(candidate -> !candidate.equals(registeredEmail)), "unknownEmail");
        String password = tc.draw(validPasswords(), "password");
        fixture.service().register(registeredEmail, password);

        InvalidCredentialsException ex = assertThrows(InvalidCredentialsException.class,
                () -> fixture.service().login(unknownEmail, password, null));

        assertEquals(INVALID_CREDENTIALS, ex.getMessage());
        assertEquals(0, fixture.sessions().size());
    }

    @HegelTest
    void logoutInvalidatesTheTokenAndIsIdempotent(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String token = registerAndLogin(tc, fixture).token();

        fixture.service().logout(token);

        assertEquals(Optional.empty(), fixture.service().currentUser(token));
        assertEquals(0, fixture.sessions().size());
        assertDoesNotThrow(() -> fixture.service().logout(token));
        assertEquals(0, fixture.sessions().size());
    }

    @HegelTest
    void unknownTokensAreNeverLoggedIn(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String forged = tc.draw(oneOf(tokensLike(), text().maxSize(60)), "forged");

        assertEquals(Optional.empty(), fixture.service().currentUser(forged));
        assertDoesNotThrow(() -> fixture.service().logout(forged));
        assertEquals(0, fixture.sessions().size());

        ActiveSession real = registerAndLogin(tc, fixture);
        tc.assume(!forged.equals(real.token()));

        assertEquals(Optional.empty(), fixture.service().currentUser(forged));
        assertDoesNotThrow(() -> fixture.service().logout(forged));
        assertEquals(1, fixture.sessions().size());
        assertTrue(fixture.service().currentUser(real.token()).isPresent());
    }

    @HegelTest
    void sessionExpiresExactlyAtItsLifetime(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        ActiveSession session = registerAndLogin(tc, fixture);
        int lead = tc.draw(integers().min(1).max((int) SESSION_LIFETIME.toMillis() - 1), "leadMillis");

        fixture.clock().advance(SESSION_LIFETIME.minusMillis(lead));
        assertTrue(fixture.service().currentUser(session.token()).isPresent());

        fixture.clock().advance(Duration.ofMillis(lead));
        assertEquals(session.expiresAt(), fixture.clock().instant());
        assertEquals(Optional.empty(), fixture.service().currentUser(session.token()));
        assertEquals(0, fixture.sessions().size());
        assertEquals(Optional.empty(), fixture.service().currentUser(session.token()));
    }

    @HegelTest
    void loginWithAPresentedSessionRotatesIt(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(validPasswords(), "password");
        fixture.service().register(email, password);
        String first = fixture.service().login(email, password, null).token();

        String second = fixture.service().login(email, password, first).token();

        assertNotEquals(first, second);
        assertEquals(Optional.empty(), fixture.service().currentUser(first));
        assertTrue(fixture.service().currentUser(second).isPresent());
        assertEquals(1, fixture.sessions().size());
    }

    @HegelTest
    void loginWithAnUnknownReplacedTokenStillSucceeds(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(validPasswords(), "password");
        String forged = tc.draw(tokensLike(), "forged");
        fixture.service().register(email, password);

        ActiveSession session = fixture.service().login(email, password, forged);

        assertTrue(fixture.service().currentUser(session.token()).isPresent());
        assertEquals(1, fixture.sessions().size());
    }

    @HegelTest
    void failedLoginDoesNotTouchThePresentedSession(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(validPasswords(), "password");
        String other = tc.draw(validPasswords().filter(candidate -> !candidate.equals(password)), "other");
        fixture.service().register(email, password);
        String first = fixture.service().login(email, password, null).token();

        assertThrows(InvalidCredentialsException.class, () -> fixture.service().login(email, other, first));

        assertTrue(fixture.service().currentUser(first).isPresent());
        assertEquals(1, fixture.sessions().size());
    }

    @HegelTest
    void concurrentSessionsAreIndependent(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(validPasswords(), "password");
        int n = tc.draw(integers().min(2).max(5), "sessions");
        fixture.service().register(email, password);

        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            tokens.add(fixture.service().login(email, password, null).token());
        }
        assertEquals(n, new HashSet<>(tokens).size());
        tokens.forEach(token -> assertTrue(fixture.service().currentUser(token).isPresent()));

        String endedToken = tokens.get(tc.draw(integers().min(0).max(n - 1), "endedIndex"));
        fixture.service().logout(endedToken);

        for (String token : tokens) {
            assertEquals(!token.equals(endedToken), fixture.service().currentUser(token).isPresent(), token);
        }
        assertEquals(n - 1, fixture.sessions().size());
    }

    @HegelTest
    void blankCredentialsAreAValidationError(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(validPasswords(), "password");
        String blankEmail = tc.draw(blankStrings(), "blankEmail");
        String blankPassword = tc.draw(blankStrings(), "blankPassword");
        fixture.service().register(email, password);

        assertLoginErrors(fixture, blankEmail, password, List.of(EMAIL_BLANK));
        assertLoginErrors(fixture, email, blankPassword, List.of(PASSWORD_BLANK));
        assertLoginErrors(fixture, blankEmail, blankPassword, List.of(EMAIL_BLANK, PASSWORD_BLANK));
        assertLoginErrors(fixture, null, password, List.of(EMAIL_BLANK));
        assertLoginErrors(fixture, email, null, List.of(PASSWORD_BLANK));
        assertEquals(0, fixture.sessions().size());
    }

    @HegelTest
    void sessionToStringOmitsTheToken(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        Instant loginTime = fixture.clock().instant();
        ActiveSession active = registerAndLogin(tc, fixture);

        Session session = fixture.sessions().findByToken(active.token()).orElseThrow();

        String rendered = session.toString();
        assertTrue(rendered.startsWith("Session["), rendered);
        assertNotNull(session.getId());
        assertTrue(rendered.contains("id=" + session.getId() + ","), rendered);
        assertTrue(rendered.contains("userId=" + active.user().id()), rendered);
        assertFalse(rendered.contains(active.token()), rendered);
        assertEquals(active.token(), session.getToken());
        assertEquals(active.user().email(), session.getUser().getEmail());
        assertEquals(loginTime, session.getCreatedAt());
        assertEquals(loginTime.plus(SESSION_LIFETIME), session.getExpiresAt());
        assertEquals(active.expiresAt(), session.getExpiresAt());
        assertFalse(session.isExpiredAt(session.getExpiresAt().minusNanos(1)));
        assertTrue(session.isExpiredAt(session.getExpiresAt()));
    }

    @HegelTest
    void randomActionSequencesAgreeWithTheModel(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(validPasswords(), "password");
        fixture.service().register(email, password);
        List<Action> actions = tc.draw(lists(sampledFrom(Action.values())).minSize(1).maxSize(15), "actions");

        List<String> issued = new ArrayList<>();
        Set<String> live = new LinkedHashSet<>();
        for (Action action : actions) {
            switch (action) {
                case LOGIN -> {
                    String token = fixture.service().login(email, password, null).token();
                    issued.add(token);
                    live.add(token);
                }
                case LOGOUT_LIVE -> {
                    if (!live.isEmpty()) {
                        String token = pick(tc, new ArrayList<>(live));
                        fixture.service().logout(token);
                        live.remove(token);
                    }
                }
                case LOGOUT_STALE -> {
                    List<String> stale = issued.stream().filter(token -> !live.contains(token)).toList();
                    if (!stale.isEmpty()) {
                        fixture.service().logout(pick(tc, stale));
                    }
                }
                case QUERY -> assertAgreesWithModel(fixture, issued, live);
                case ADVANCE_PAST_EXPIRY -> {
                    fixture.clock().advance(SESSION_LIFETIME);
                    live.clear();
                }
            }
        }
        assertAgreesWithModel(fixture, issued, live);
    }

    @Test
    void nullTokenIsNotLoggedInAndLogoutOfNullIsANoOp() {
        ServiceFixture fixture = ServiceFixture.fresh();

        assertEquals(Optional.empty(), fixture.service().currentUser(null));
        assertDoesNotThrow(() -> fixture.service().logout(null));
        assertEquals(0, fixture.sessions().size());
    }

    @Test
    void tokenGeneratorProducesDistinctUrlSafeTokens() {
        SessionTokenGenerator generator = new SessionTokenGenerator();
        Set<String> tokens = new HashSet<>();

        for (int i = 0; i < 1_000; i++) {
            String token = generator.generate();
            assertTrue(TOKEN_SHAPE.matcher(token).matches(), token);
            assertTrue(tokens.add(token), "duplicate token " + token);
        }
    }

    private static ActiveSession registerAndLogin(TestCase tc, ServiceFixture fixture) {
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(validPasswords(), "password");
        fixture.service().register(email, password);
        return fixture.service().login(email, password, null);
    }

    private static void assertLoginErrors(ServiceFixture fixture, String email, String password, List<String> expected) {
        InvalidLoginException ex = assertThrows(InvalidLoginException.class,
                () -> fixture.service().login(email, password, null));
        assertEquals(expected, ex.getErrors());
    }

    private static void assertAgreesWithModel(ServiceFixture fixture, List<String> issued, Set<String> live) {
        for (String token : issued) {
            assertEquals(live.contains(token), fixture.service().currentUser(token).isPresent(), token);
        }
    }

    private static String pick(TestCase tc, List<String> candidates) {
        return candidates.get(tc.draw(integers().min(0).max(candidates.size() - 1), "index"));
    }

    private static String pad(TestCase tc, String value) {
        return " ".repeat(tc.draw(integers().min(0).max(3), "leading"))
                + value
                + " ".repeat(tc.draw(integers().min(0).max(3), "trailing"));
    }
}
