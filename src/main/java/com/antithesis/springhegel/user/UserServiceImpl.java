package com.antithesis.springhegel.user;

import java.time.Instant;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final RegistrationValidator validator;
    private final PasswordEncoder passwordEncoder;

    public UserServiceImpl(
            UserRepository userRepository,
            RegistrationValidator validator,
            PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.validator = validator;
        this.passwordEncoder = passwordEncoder;
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

        User user = new User(normalizedEmail, passwordEncoder.encode(trimmedPassword), Instant.now());
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
}
