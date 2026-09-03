package com.antithesis.springhegel.user;

import java.time.Instant;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * A fresh, Spring-free {@link UserService} with in-memory fakes and a controllable clock. Built
 * anew inside every Hegel test body so no state survives between draws.
 */
record ServiceFixture(
        InMemoryUserRepository repository,
        InMemorySessionRepository sessions,
        MutableClock clock,
        UserService service) {

    // Low strength keeps the many Hegel draws fast; the production strength is configured in PasswordConfig.
    static final PasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    static final Instant START = Instant.parse("2026-09-03T12:00:00Z");

    static ServiceFixture fresh() {
        InMemoryUserRepository repository = new InMemoryUserRepository();
        InMemorySessionRepository sessions = new InMemorySessionRepository();
        MutableClock clock = new MutableClock(START);
        UserService service = new UserServiceImpl(
                repository, sessions, new RegistrationValidator(), ENCODER, new SessionTokenGenerator(), clock);
        return new ServiceFixture(repository, sessions, clock, service);
    }
}
