package com.antithesis.springhegel.user;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserServiceImpl implements UserService {

    static final Duration SESSION_LIFETIME = Duration.ofHours(12);
    static final String EMAIL_BLANK = "Email must not be blank";
    static final String PASSWORD_BLANK = "Password must not be blank";

    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final RegistrationValidator validator;
    private final PasswordEncoder passwordEncoder;
    private final SessionTokenGenerator tokenGenerator;
    private final Clock clock;
    /**
     * Hash compared against when the email is unknown, so unknown-email and wrong-password logins
     * perform the same BCrypt work and cannot be told apart by response time.
     */
    private final String unknownUserHash;

    public UserServiceImpl(
            UserRepository userRepository,
            SessionRepository sessionRepository,
            RegistrationValidator validator,
            PasswordEncoder passwordEncoder,
            SessionTokenGenerator tokenGenerator,
            Clock clock) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.validator = validator;
        this.passwordEncoder = passwordEncoder;
        this.tokenGenerator = tokenGenerator;
        this.clock = clock;
        this.unknownUserHash = passwordEncoder.encode("unknown-user-placeholder");
    }

    @Override
    @Transactional
    public RegisteredUser register(String email, String password) {
        String normalizedEmail = validator.normalizeEmail(email);
        String trimmedPassword = password == null ? "" : password.strip();

        List<String> errors = validator.validate(normalizedEmail, trimmedPassword);
        if (!errors.isEmpty()) {
            throw new InvalidRegistrationException(errors);
        }
        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new EmailAlreadyRegisteredException();
        }

        User user = new User(normalizedEmail, passwordEncoder.encode(trimmedPassword), clock.instant());
        User saved;
        try {
            saved = userRepository.save(user);
        } catch (DataIntegrityViolationException raceWithConcurrentRegistration) {
            // The unique constraint on users.email is the real guarantee; the existsByEmail
            // pre-check above only gives a friendlier fast path.
            throw new EmailAlreadyRegisteredException();
        }
        return new RegisteredUser(saved.getId(), saved.getEmail());
    }

    @Override
    @Transactional
    public ActiveSession login(String email, String password, String replacedToken) {
        String normalizedEmail = validator.normalizeEmail(email);
        String trimmedPassword = password == null ? "" : password.strip();

        List<String> errors = new ArrayList<>();
        if (normalizedEmail.isBlank()) {
            errors.add(EMAIL_BLANK);
        }
        if (trimmedPassword.isBlank()) {
            errors.add(PASSWORD_BLANK);
        }
        if (!errors.isEmpty()) {
            throw new InvalidLoginException(errors);
        }

        Optional<User> user = userRepository.findByEmail(normalizedEmail);
        String hash = user.map(User::getPasswordHash).orElse(unknownUserHash);
        // Always run the comparison before deciding, so both failure causes do comparable work.
        boolean matches = passwordEncoder.matches(trimmedPassword, hash);
        if (user.isEmpty() || !matches) {
            throw new InvalidCredentialsException();
        }

        if (replacedToken != null) {
            sessionRepository.findByToken(replacedToken).ifPresent(sessionRepository::delete);
        }

        User owner = user.get();
        Instant now = clock.instant();
        Session saved = sessionRepository.save(
                new Session(tokenGenerator.generate(), owner, now, now.plus(SESSION_LIFETIME)));
        return new ActiveSession(
                saved.getToken(), saved.getExpiresAt(), new RegisteredUser(owner.getId(), owner.getEmail()));
    }

    @Override
    @Transactional
    public void logout(String token) {
        if (token == null) {
            return;
        }
        sessionRepository.findByToken(token).ifPresent(sessionRepository::delete);
    }

    @Override
    @Transactional
    public Optional<RegisteredUser> currentUser(String token) {
        return liveSession(token)
                .map(session -> new RegisteredUser(session.getUser().getId(), session.getUser().getEmail()));
    }

    @Override
    @Transactional
    public void deleteCurrentUser(String token) {
        Session session = liveSession(token).orElseThrow(NotLoggedInException::new);
        User owner = session.getUser();
        // Sessions first: sessions.user_id is a not-null foreign key. The transaction makes the
        // two deletions all-or-nothing.
        sessionRepository.deleteAllByUser(owner);
        userRepository.delete(owner);
    }

    /**
     * The single definition of "logged in": the token is present, known and not expired. An
     * expired session encountered here is deleted.
     */
    private Optional<Session> liveSession(String token) {
        if (token == null) {
            return Optional.empty();
        }
        Optional<Session> session = sessionRepository.findByToken(token);
        if (session.isEmpty()) {
            return Optional.empty();
        }
        if (session.get().isExpiredAt(clock.instant())) {
            sessionRepository.delete(session.get());
            return Optional.empty();
        }
        return session;
    }
}
