package com.antithesis.springhegel.user.web;

import static com.antithesis.springhegel.user.EmailPasswordGenerators.randomizeCase;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.tokensLike;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.validPasswords;
import static com.antithesis.springhegel.user.web.UserApi.TOKEN_SHAPE;
import static com.antithesis.springhegel.user.web.UserApi.idOf;
import static com.antithesis.springhegel.user.web.UserApi.sessionCookie;
import static dev.hegel.Generators.booleans;
import static dev.hegel.Generators.fromRegex;
import static org.assertj.core.api.Assertions.assertThat;

import dev.hegel.HegelTest;
import dev.hegel.Invariant;
import dev.hegel.Pool;
import dev.hegel.Rule;
import dev.hegel.Stateful;
import dev.hegel.TestCase;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The user lifecycle over HTTP as a Hegel <em>stateful</em> property. Where
 * {@link UserLifecycleIntegrationPropertyTest} scripts the six steps in a fixed order, here the
 * engine chooses the actions: {@link Stateful#run} enables a random subset of the {@link Rule
 * rules} per test case, picks which one runs next, checks the {@link Invariant invariants} against
 * the resulting state, and shrinks a failing run to the shortest action sequence that breaks the
 * contract. A small model — is the email registered, which password is current, which of the
 * issued cookies are live — predicts every response.
 *
 * <p>Rules branch on the model instead of assuming it: registering twice must be a {@code 409},
 * logging in while unregistered must be a {@code 401}, deleting with a dead cookie must be a
 * {@code 401}. So nearly every engine-chosen step is a real check; only the two rules that need a
 * previously issued cookie are skipped while none exists (a draw from an empty {@link Pool}
 * rejects just that rule, not the test case).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserLifecycleStatefulPropertyTest {

    /** Keeps every draw's email unique, even across failed or shrunk runs that left rows behind. */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Autowired
    private MockMvcTester mvc;

    @HegelTest
    void randomHttpLifecyclesAgreeWithTheModel(TestCase tc) {
        String local = tc.draw(fromRegex("[a-z0-9]{1,12}").fullmatch(true), "local");
        String password = tc.draw(validPasswords(), "password");
        // Test-owned domain plus a per-JVM sequence: never collides with the wiring tests' fixed
        // example.com emails nor with leftovers of an earlier failed draw.
        String suffix = "." + SEQUENCE.incrementAndGet() + "@lifecycle.test";

        UserLifecycleMachine machine = new UserLifecycleMachine(new UserApi(mvc), tc, local, suffix, password);
        Stateful.run(machine, tc);
        machine.cleanUp();
    }

    /**
     * The state machine. Every {@code @Rule} and {@code @Invariant} takes the current
     * {@link TestCase} so all its choices — values, which pooled cookie, forged or no cookie — are
     * engine draws that replay and shrink.
     */
    static final class UserLifecycleMachine {

        private final UserApi api;
        private final String local;
        private final String suffix;
        private final String email;

        // --- the model ---
        private boolean registered;
        /** The credential a log-in must currently present; redrawn on every successful registration. */
        private String password;
        /** Id of the current registration, {@code null} while unregistered. */
        private Long userId;
        /** Every cookie the application ever issued in this test case, as the engine's pool ... */
        private final Pool<Cookie> issued;
        /** ... and as a plain list, for the invariant that walks all of them. */
        private final List<Cookie> issuedCookies = new ArrayList<>();
        /** Token values the model considers live. */
        private final Set<String> live = new HashSet<>();

        UserLifecycleMachine(UserApi api, TestCase tc, String local, String suffix, String initialPassword) {
            this.api = api;
            this.local = local;
            this.suffix = suffix;
            this.email = local + suffix;
            this.password = initialPassword;
            this.issued = new Pool<>(tc);
        }

        // --- rules ---

        @Rule
        void register(TestCase tc) {
            if (registered) {
                MvcTestResult again = api.register(email, password);
                assertThat(again).hasStatus(HttpStatus.CONFLICT);
                assertThat(again).bodyJson().extractingPath("$.code").isEqualTo("EMAIL_ALREADY_REGISTERED");
                return;
            }
            password = tc.draw(validPasswords(), "password");
            MvcTestResult result = api.register(email, password);
            assertThat(result).hasStatus(HttpStatus.CREATED);
            assertThat(result).bodyJson().extractingPath("$.email").isEqualTo(email);
            long id = idOf(result);
            if (userId != null) {
                assertThat(id).as("a re-registration is a new user").isNotEqualTo(userId);
            }
            registered = true;
            userId = id;
        }

        @Rule
        void logIn(TestCase tc) {
            // Only the fixed-length local part is case-randomized (one draw per character).
            String loginEmail = randomizeCase(tc, local) + suffix;
            MvcTestResult result = api.login(loginEmail, password);
            if (!registered) {
                assertInvalidCredentials(result);
                return;
            }
            assertThat(result).hasStatus(HttpStatus.CREATED);
            assertThat(result).bodyJson().extractingPath("$.email").isEqualTo(email);
            assertThat(idOf(result)).isEqualTo(userId);
            assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("HttpOnly");
            Cookie cookie = sessionCookie(result);
            assertThat(cookie.getValue()).matches(TOKEN_SHAPE);
            issued.add(cookie);
            issuedCookies.add(cookie);
            live.add(cookie.getValue());
        }

        @Rule
        void logInWithTheWrongPassword(TestCase tc) {
            String other = tc.draw(validPasswords().filter(candidate -> !candidate.equals(password)), "other");
            assertInvalidCredentials(api.login(email, other));
        }

        @Rule
        void logOut(TestCase tc) {
            Cookie cookie = tc.draw(issued.reusable(), "cookie");
            MvcTestResult result = api.logout(cookie);
            // Logout is idempotent: a dead cookie gets the same answer as a live one.
            assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
            assertClearsCookie(result);
            live.remove(cookie.getValue());
        }

        @Rule
        void deleteSelf(TestCase tc) {
            Cookie cookie = tc.draw(issued.reusable(), "cookie");
            MvcTestResult result = api.deleteMe(cookie);
            if (!live.contains(cookie.getValue())) {
                assertNotLoggedIn(result);
                return;
            }
            assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
            assertThat(result).bodyText().isEmpty();
            assertClearsCookie(result);
            registered = false;
            userId = null;
            // Deleting the account logs the user out everywhere, not just on this device.
            live.clear();
        }

        @Rule
        void deleteWithoutASession(TestCase tc) {
            boolean forged = tc.draw(booleans(), "forged");
            Cookie cookie = forged
                    ? new Cookie(SessionController.SESSION_COOKIE, tc.draw(tokensLike(), "token"))
                    : null;
            assertNotLoggedIn(api.deleteMe(cookie));
        }

        // --- invariants ---

        @Invariant
        void issuedCookiesAgreeWithTheModel(TestCase tc) {
            for (Cookie cookie : issuedCookies) {
                MvcTestResult result = api.session(cookie);
                if (live.contains(cookie.getValue())) {
                    assertThat(result).hasStatus(HttpStatus.OK);
                    assertThat(result).bodyJson().extractingPath("$.email").isEqualTo(email);
                    assertThat(idOf(result)).isEqualTo(userId);
                } else {
                    assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
                    assertThat(result).bodyText().isEmpty();
                }
            }
        }

        /** Side-effect free on both branches, so checking the state never changes it. */
        @Invariant
        void registrationAgreesWithTheModel(TestCase tc) {
            if (registered) {
                MvcTestResult result = api.register(email, password);
                assertThat(result).hasStatus(HttpStatus.CONFLICT);
                assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("EMAIL_ALREADY_REGISTERED");
            } else {
                assertInvalidCredentials(api.login(email, password));
            }
        }

        // --- cleanup ---

        /** Leaves the shared database as it was found and proves it: nothing is left for this email. */
        void cleanUp() {
            if (registered) {
                MvcTestResult login = api.login(email, password);
                assertThat(login).hasStatus(HttpStatus.CREATED);
                assertThat(api.deleteMe(sessionCookie(login))).hasStatus(HttpStatus.NO_CONTENT);
            }
            for (Cookie cookie : issuedCookies) {
                assertThat(api.session(cookie)).hasStatus(HttpStatus.NO_CONTENT);
            }
            assertInvalidCredentials(api.login(email, password));
        }

        private static void assertInvalidCredentials(MvcTestResult result) {
            assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_CREDENTIALS");
            assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
        }

        private static void assertNotLoggedIn(MvcTestResult result) {
            assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("NOT_LOGGED_IN");
            assertClearsCookie(result);
        }

        private static void assertClearsCookie(MvcTestResult result) {
            assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                    .startsWith("SESSION=;").contains("Max-Age=0");
        }
    }
}
