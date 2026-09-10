package com.antithesis.springhegel.user.web;

import static com.antithesis.springhegel.user.EmailPasswordGenerators.randomizeCase;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.validPasswords;
import static com.antithesis.springhegel.user.web.UserApi.TOKEN_SHAPE;
import static com.antithesis.springhegel.user.web.UserApi.idOf;
import static com.antithesis.springhegel.user.web.UserApi.sessionCookie;
import static dev.hegel.Generators.fromRegex;
import static org.assertj.core.api.Assertions.assertThat;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import jakarta.servlet.http.Cookie;
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
 * A Hegel property that drives the whole stack over HTTP: register → log in → log out → log in →
 * delete self → register the same email again — then cleans up after itself. Because it runs
 * against the real controllers, JPA and H2, it is the test that proves the deletion order against
 * the {@code sessions.user_id} foreign key.
 *
 * <p>{@link UserLifecycleStatefulPropertyTest} checks the same contract with Hegel's stateful
 * testing, letting the engine choose the order of the steps.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserLifecycleIntegrationPropertyTest {

    /** Keeps every draw's email unique, even across failed or shrunk runs that left rows behind. */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Autowired
    private MockMvcTester mvc;

    @HegelTest
    void aUserCanRegisterLogInLogOutLogInAgainDeleteItselfAndBeRegisteredAgain(TestCase tc) {
        UserApi api = new UserApi(mvc);
        String local = tc.draw(fromRegex("[a-z0-9]{1,12}").fullmatch(true), "local");
        // Drawn before the sequence number is involved: the randomizer draws once per character, and
        // anything whose length varies between runs would make the failure impossible to replay.
        String loginLocal = randomizeCase(tc, local);
        String password = tc.draw(validPasswords(), "password");
        String password2 = tc.draw(validPasswords(), "password2");
        // Test-owned domain: never collides with the fixed example.com emails of the wiring tests.
        long sequence = SEQUENCE.incrementAndGet();
        String email = local + "." + sequence + "@lifecycle.test";
        String loginEmail = loginLocal + "." + sequence + "@lifecycle.test";

        // 1. A new user is created
        MvcTestResult registered = api.register(email, password);
        assertThat(registered).hasStatus(HttpStatus.CREATED);
        assertThat(registered).bodyJson().extractingPath("$.email").isEqualTo(email);
        long firstId = idOf(registered);

        // 2. The user can log in
        MvcTestResult firstLogin = api.login(email, password);
        assertThat(firstLogin).hasStatus(HttpStatus.CREATED);
        Cookie first = sessionCookie(firstLogin);
        assertThat(first.getValue()).matches(TOKEN_SHAPE);
        MvcTestResult loggedIn = api.session(first);
        assertThat(loggedIn).hasStatus(HttpStatus.OK);
        assertThat(loggedIn).bodyJson().extractingPath("$.email").isEqualTo(email);

        // 3. The user can log out
        assertThat(api.logout(first)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(api.session(first)).hasStatus(HttpStatus.NO_CONTENT);

        // 4. The user can log in (again, with a case variant of the email and no cookie)
        MvcTestResult secondLogin = api.login(loginEmail, password);
        assertThat(secondLogin).hasStatus(HttpStatus.CREATED);
        Cookie second = sessionCookie(secondLogin);
        assertThat(second.getValue()).isNotEqualTo(first.getValue());
        assertThat(api.session(second)).hasStatus(HttpStatus.OK);

        // 5. The user can delete its own user
        MvcTestResult deleted = api.deleteMe(second);
        assertThat(deleted).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(deleted).bodyText().isEmpty();
        assertThat(deleted.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .startsWith("SESSION=;").contains("Max-Age=0");
        assertThat(api.session(second)).hasStatus(HttpStatus.NO_CONTENT);
        MvcTestResult deletedAgain = api.deleteMe(second);
        assertThat(deletedAgain).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(deletedAgain).bodyJson().extractingPath("$.code").isEqualTo("NOT_LOGGED_IN");
        assertInvalidCredentials(api.login(email, password));

        // 6. A new user with the same email can be created
        MvcTestResult reRegistered = api.register(email, password2);
        assertThat(reRegistered).hasStatus(HttpStatus.CREATED);
        assertThat(reRegistered).bodyJson().extractingPath("$.email").isEqualTo(email);
        assertThat(idOf(reRegistered)).isNotEqualTo(firstId);

        // Cleanup is part of the property: delete the re-created user and prove nothing is left.
        MvcTestResult thirdLogin = api.login(email, password2);
        assertThat(thirdLogin).hasStatus(HttpStatus.CREATED);
        assertThat(api.deleteMe(sessionCookie(thirdLogin))).hasStatus(HttpStatus.NO_CONTENT);
        assertInvalidCredentials(api.login(email, password2));
    }

    private static void assertInvalidCredentials(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_CREDENTIALS");
    }
}
