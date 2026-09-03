package com.antithesis.springhegel.user;

import static com.antithesis.springhegel.user.EmailPasswordGenerators.invalidEmails;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.invalidPasswords;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.randomizeCase;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.validEmails;
import static com.antithesis.springhegel.user.EmailPasswordGenerators.validPasswords;
import static com.antithesis.springhegel.user.ServiceFixture.ENCODER;
import static dev.hegel.Generators.integers;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.hegel.HegelTest;
import dev.hegel.TestCase;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class UserServicePropertyTest {

    @HegelTest
    void validInputRegistersTheNormalizedUser(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(validPasswords(), "password");

        RegisteredUser registered = fixture.service().register(email, password);

        assertNotNull(registered.id());
        assertEquals(email, registered.email());
        assertEquals(1, fixture.repository().size());
    }

    @HegelTest
    void registeredUserIsPersistedWithAHashedPassword(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(validPasswords(), "password");

        fixture.service().register(email, password);
        Optional<User> stored = fixture.repository().findByEmail(email);

        assertTrue(stored.isPresent());
        User user = stored.get();
        assertNotEquals(password, user.getPasswordHash());
        assertTrue(ENCODER.matches(password, user.getPasswordHash()));
        assertNotNull(user.getCreatedAt());
        assertFalse(user.toString().contains(user.getPasswordHash()), user.toString());
        assertTrue(user.toString().contains(email), user.toString());
    }

    @HegelTest
    void registeredUserCreatedAtComesFromTheClock(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(validPasswords(), "password");

        fixture.service().register(email, password);

        assertEquals(fixture.clock().instant(), fixture.repository().findByEmail(email).get().getCreatedAt());
    }

    @HegelTest
    void registeringAnyVariantOfARegisteredEmailConflicts(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(validPasswords(), "password");
        String variant = " ".repeat(tc.draw(integers().min(0).max(3), "leading"))
                + randomizeCase(tc, email)
                + " ".repeat(tc.draw(integers().min(0).max(3), "trailing"));
        String otherPassword = tc.draw(validPasswords(), "otherPassword");

        fixture.service().register(email, password);

        assertThrows(EmailAlreadyRegisteredException.class,
                () -> fixture.service().register(variant, otherPassword));
        assertEquals(1, fixture.repository().size());
    }

    @HegelTest
    void invalidInputIsRejectedAndNothingIsStored(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String validEmail = tc.draw(validEmails(), "validEmail");
        String validPassword = tc.draw(validPasswords(), "validPassword");
        String invalidEmail = tc.draw(invalidEmails(), "invalidEmail");
        String invalidPassword = tc.draw(invalidPasswords(), "invalidPassword");

        InvalidRegistrationException badEmail = assertThrows(InvalidRegistrationException.class,
                () -> fixture.service().register(invalidEmail, validPassword));
        InvalidRegistrationException badPassword = assertThrows(InvalidRegistrationException.class,
                () -> fixture.service().register(validEmail, invalidPassword));

        assertFalse(badEmail.getErrors().isEmpty());
        assertFalse(badPassword.getErrors().isEmpty());
        assertEquals(0, fixture.repository().size());
    }

    @HegelTest
    void surroundingWhitespaceIsTrimmedBeforeRegistration(TestCase tc) {
        ServiceFixture fixture = ServiceFixture.fresh();
        String email = tc.draw(validEmails(), "email");
        String password = tc.draw(validPasswords(), "password");
        String paddedEmail = pad(tc, email);
        String paddedPassword = pad(tc, password);

        RegisteredUser registered = fixture.service().register(paddedEmail, paddedPassword);

        assertEquals(email, registered.email());
        assertTrue(fixture.repository().findByEmail(email).isPresent());
        assertTrue(ENCODER.matches(password, fixture.repository().findByEmail(email).get().getPasswordHash()));
    }

    private static String pad(TestCase tc, String value) {
        return " ".repeat(tc.draw(integers().min(0).max(3), "leading"))
                + value
                + " ".repeat(tc.draw(integers().min(0).max(3), "trailing"));
    }

    @Test
    void uniqueConstraintViolationOnSaveIsReportedAsConflict() {
        UserRepository racingRepository = new UserRepository() {
            @Override
            public User save(User user) {
                throw new DataIntegrityViolationException("users.email unique constraint");
            }

            @Override
            public boolean existsByEmail(String email) {
                return false;
            }

            @Override
            public Optional<User> findByEmail(String email) {
                return Optional.empty();
            }
        };
        UserService service = new UserServiceImpl(
                racingRepository,
                new InMemorySessionRepository(),
                new RegistrationValidator(),
                ENCODER,
                new SessionTokenGenerator(),
                new MutableClock(ServiceFixture.START));

        assertThrows(EmailAlreadyRegisteredException.class,
                () -> service.register("race@example.com", "Str0ng!pass"));
    }

    @Test
    void nullEmailIsRejected() {
        InvalidRegistrationException ex = assertThrows(InvalidRegistrationException.class,
                () -> ServiceFixture.fresh().service().register(null, "Str0ng!pass"));

        assertEquals(List.of("Email must not be blank"), ex.getErrors());
    }

    @Test
    void nullPasswordIsRejected() {
        InvalidRegistrationException ex = assertThrows(InvalidRegistrationException.class,
                () -> ServiceFixture.fresh().service().register("someone@example.com", null));

        assertEquals(List.of("Password must not be blank"), ex.getErrors());
    }
}
