package com.antithesis.springhegel.user;

import static com.antithesis.springhegel.user.EmailPasswordGenerators.validPasswords;
import static com.antithesis.springhegel.user.UserServiceImpl.SESSION_LIFETIME;
import static dev.hegel.Generators.longs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The lazy removal of an expired session must survive the refusal that follows it, on the real
 * JPA + H2 stack. The Spring-free properties in {@link UserServiceDeletionPropertyTest} run
 * against in-memory fakes with no transactions, so they cannot see a rollback: {@code
 * deleteCurrentUser} deletes the expired row and then throws {@code NotLoggedInException}, and
 * without {@code noRollbackFor} that exception rolled the deletion back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExpiredSessionDeletionOnDatabasePropertyTest {

    /** A Clock the test can move; replaces the application's system clock bean. */
    static final class SettableClock extends Clock {
        volatile Instant now = START;

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @TestConfiguration
    static class ClockOverride {
        @Bean
        @Primary
        SettableClock settableClock() {
            return new SettableClock();
        }
    }

    private static final Instant START = Instant.parse("2026-09-03T12:00:00Z");
    private static final AtomicLong SEQUENCE = new AtomicLong();

    @Autowired
    private UserService service;

    @Autowired
    private SettableClock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MockMvcTester mvc;

    private int sessionRows(String token) {
        return jdbc.queryForObject("select count(*) from sessions where token = ?", Integer.class, token);
    }

    private int userRows(String email) {
        return jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email);
    }

    /**
     * Registers and logs in a fresh user at {@link #START}, then moves the clock past the
     * session's expiry. The clock stays on whole microseconds: the database column has
     * microsecond precision and rounds, so finer instants would blur the expiry boundary.
     */
    private String[] expiredLogin(TestCase tc) {
        String password = tc.draw(validPasswords(), "password");
        String email = "expired" + SEQUENCE.incrementAndGet() + "@example.com";
        clock.now = START;
        service.register(email, password);
        ActiveSession session = service.login(email, password, null);
        long pastExpiryMicros =
                tc.draw(longs().min(0).max(Duration.ofDays(400).toNanos() / 1_000), "pastExpiryMicros");
        clock.now = START.plus(SESSION_LIFETIME).plus(pastExpiryMicros, ChronoUnit.MICROS);
        return new String[] {email, session.token()};
    }

    private void cleanup(String email) {
        jdbc.update("delete from sessions where user_id in (select id from users where email = ?)", email);
        jdbc.update("delete from users where email = ?", email);
    }

    @HegelTest
    void deleteCurrentUserWithAnExpiredTokenIsRefusedAndTheExpiredRowIsGoneFromTheDatabase(TestCase tc) {
        String[] user = expiredLogin(tc);
        try {
            assertThatThrownBy(() -> service.deleteCurrentUser(user[1])).isInstanceOf(NotLoggedInException.class);
            assertThat(userRows(user[0])).as("user survives the refusal").isEqualTo(1);
            assertThat(sessionRows(user[1])).as("expired session row after deleteCurrentUser").isZero();
        } finally {
            cleanup(user[0]);
        }
    }

    @HegelTest
    void deleteMeWithAnExpiredCookieIs401AndTheExpiredRowIsGoneFromTheDatabase(TestCase tc) {
        String[] user = expiredLogin(tc);
        try {
            MvcTestResult result =
                    mvc.delete().uri("/api/users/me").cookie(new Cookie("SESSION", user[1])).exchange();
            assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("NOT_LOGGED_IN");
            assertThat(userRows(user[0])).isEqualTo(1);
            assertThat(sessionRows(user[1])).as("expired session row after DELETE /api/users/me").isZero();
        } finally {
            cleanup(user[0]);
        }
    }
}
