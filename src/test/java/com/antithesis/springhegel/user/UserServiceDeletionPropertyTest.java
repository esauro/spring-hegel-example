package com.antithesis.springhegel.user;

import static com.antithesis.springhegel.user.EmailPasswordGenerators.randomizeCase;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.tokensLike;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.validEmails;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.validPasswords;
import static com.antithesis.springhegel.user.UserServiceImpl.SESSION_LIFETIME;
import static dev.hegel.Generators.integers;
import static dev.hegel.Generators.lists;
import static dev.hegel.Generators.sampledFrom;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Properties of account self-deletion: who may delete, what disappears, and what is refused. */
class UserServiceDeletionPropertyTest {

    private enum DeadToken { NULL, FORGED, LOGGED_OUT }

    private enum Action { REGISTER, LOGIN, LOGOUT_LIVE, DELETE_LIVE, DELETE_STALE, QUERY, ADVANCE_PAST_EXPIRY }

    @HegelTest
    void deletingTheCurrentUserRemovesTheUserAndEveryOneOfItsSessions(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(validPasswords(), "password");
        fixture.service().register(email, password);
        List<String> tokens = loginTimes(tc, fixture, email, password, "logins");
        String presented = pick(tc, tokens);

        fixture.service().deleteCurrentUser(presented);

        assertFalse(fixture.repository().existsByEmail(email));
        assertEquals(0, fixture.repository().size());
        assertEquals(0, fixture.sessions().size());
        for (String token : tokens) {
            assertEquals(Optional.empty(), fixture.service().currentUser(token), token);
        }
        assertThrows(InvalidCredentialsException.class, () -> fixture.service().login(email, password, null));
        // Not idempotent: the session is gone, so a second attempt is refused.
        assertThrows(NotLoggedInException.class, () -> fixture.service().deleteCurrentUser(presented));
    }

    @HegelTest
    void deletionWithoutALiveSessionIsRefusedAndChangesNothing(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(validPasswords(), "password");
        fixture.service().register(email, password);
        String live = fixture.service().login(email, password, null).token();
        String dead = switch (tc.draw(sampledFrom(DeadToken.values()), "deadKind")) {
            case NULL -> null;
            case FORGED -> tc.draw(tokensLike().filter(candidate -> !candidate.equals(live)), "forged");
            case LOGGED_OUT -> {
                String endedToken = fixture.service().login(email, password, null).token();
                fixture.service().logout(endedToken);
                yield endedToken;
            }
        };

        NotLoggedInException ex = assertThrows(NotLoggedInException.class,
                () -> fixture.service().deleteCurrentUser(dead));

        assertEquals("Not logged in", ex.getMessage());
        assertTrue(fixture.repository().existsByEmail(email));
        assertTrue(fixture.service().currentUser(live).isPresent());
        assertEquals(1, fixture.sessions().size());
    }

    @HegelTest
    void deletionWithAnExpiredSessionIsRefusedAndTheExpiredRowIsRemoved(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(validPasswords(), "password");
        fixture.service().register(email, password);
        String token = fixture.service().login(email, password, null).token();
        long extraMillis = tc.draw(integers().min(0).max(86_400_000), "extraMillis");
        fixture.clock().advance(SESSION_LIFETIME.plus(Duration.ofMillis(extraMillis)));

        assertThrows(NotLoggedInException.class, () -> fixture.service().deleteCurrentUser(token));

        assertEquals(0, fixture.sessions().size());
        assertTrue(fixture.repository().existsByEmail(email));
        assertNotNull(fixture.service().login(email, password, null));
    }

    @HegelTest
    void aDeletedEmailCanBeRegisteredAgainAsANewUser(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(validPasswords(), "password");
        String password2 = tc.draw(validPasswords(), "password2");
        RegisteredUser first = fixture.service().register(email, password);
        String token = fixture.service().login(email, password, null).token();
        fixture.service().deleteCurrentUser(token);

        RegisteredUser second = fixture.service().register(pad(tc, randomizeCase(tc, email)), password2);

        assertEquals(email, second.email());
        assertNotEquals(first.id(), second.id());
        assertNotNull(fixture.service().login(email, password2, null));
        assertEquals(Optional.empty(), fixture.service().currentUser(token));
        assertEquals(1, fixture.repository().size());
    }

    @HegelTest
    void deletingOneUserLeavesOtherUsersUntouched(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String emailA = tc.draw(validEmails(), "emailA");
        String emailB = tc.draw(validEmails().filter(candidate -> !candidate.equals(emailA)), "emailB");
        String passwordA = tc.draw(validPasswords(), "passwordA");
        String passwordB = tc.draw(validPasswords(), "passwordB");
        fixture.service().register(emailA, passwordA);
        fixture.service().register(emailB, passwordB);
        List<String> tokensA = loginTimes(tc, fixture, emailA, passwordA, "loginsA");
        List<String> tokensB = loginTimes(tc, fixture, emailB, passwordB, "loginsB");

        fixture.service().deleteCurrentUser(pick(tc, tokensA));

        assertFalse(fixture.repository().existsByEmail(emailA));
        assertTrue(fixture.repository().existsByEmail(emailB));
        for (String token : tokensA) {
            assertEquals(Optional.empty(), fixture.service().currentUser(token), token);
        }
        for (String token : tokensB) {
            assertTrue(fixture.service().currentUser(token).isPresent(), token);
        }
        assertEquals(tokensB.size(), fixture.sessions().size());
        assertEquals(1, fixture.repository().size());
    }

    @HegelTest
    void randomLifecycleSequencesAgreeWithTheModel(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(validPasswords(), "password");
        List<Action> actions = tc.draw(lists(sampledFrom(Action.values())).minSize(1).maxSize(20), "actions");

        boolean registered = false;
        List<String> issued = new ArrayList<>();
        Set<String> live = new LinkedHashSet<>();
        for (Action action : actions) {
            switch (action) {
                case REGISTER -> {
                    if (registered) {
                        assertThrows(EmailAlreadyRegisteredException.class,
                                () -> fixture.service().register(email, password));
                    } else {
                        fixture.service().register(email, password);
                        registered = true;
                    }
                }
                case LOGIN -> {
                    if (registered) {
                        String token = fixture.service().login(email, password, null).token();
                        issued.add(token);
                        live.add(token);
                    } else {
                        assertThrows(InvalidCredentialsException.class,
                                () -> fixture.service().login(email, password, null));
                    }
                }
                case LOGOUT_LIVE -> {
                    if (!live.isEmpty()) {
                        String token = pick(tc, new ArrayList<>(live));
                        fixture.service().logout(token);
                        live.remove(token);
                    }
                }
                case DELETE_LIVE -> {
                    if (!live.isEmpty()) {
                        fixture.service().deleteCurrentUser(pick(tc, new ArrayList<>(live)));
                        registered = false;
                        live.clear();
                    }
                }
                case DELETE_STALE -> {
                    List<String> stale = issued.stream().filter(token -> !live.contains(token)).toList();
                    String token = stale.isEmpty() ? null : pick(tc, stale);
                    assertThrows(NotLoggedInException.class, () -> fixture.service().deleteCurrentUser(token));
                }
                case QUERY -> assertAgreesWithModel(fixture, email, registered, issued, live);
                case ADVANCE_PAST_EXPIRY -> {
                    fixture.clock().advance(SESSION_LIFETIME);
                    live.clear();
                }
            }
        }
        assertAgreesWithModel(fixture, email, registered, issued, live);
    }

    private static List<String> loginTimes(
            TestCase tc, ServiceFixture fixture, String email, String password, String label) {
        long count = tc.draw(integers().min(1).max(4), label);
        List<String> tokens = new ArrayList<>();
        for (long i = 0; i < count; i++) {
            tokens.add(fixture.service().login(email, password, null).token());
        }
        return tokens;
    }

    private static void assertAgreesWithModel(
            ServiceFixture fixture, String email, boolean registered, List<String> issued, Set<String> live) {
        for (String token : issued) {
            assertEquals(live.contains(token), fixture.service().currentUser(token).isPresent(), token);
        }
        assertEquals(registered, fixture.repository().existsByEmail(email));
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
