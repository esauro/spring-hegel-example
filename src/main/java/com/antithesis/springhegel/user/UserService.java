package com.antithesis.springhegel.user;

/**
 * Business contract for registering users.
 */
public interface UserService {

    /**
     * Registers a new user.
     *
     * <p>Both arguments may be {@code null} or carry leading/trailing whitespace; they are
     * trimmed and the email is lowercased before validation. On success the user is persisted
     * with a hashed password and the created user is returned.
     *
     * @throws InvalidRegistrationException if the email or password violates a validation rule
     * @throws EmailAlreadyRegisteredException if the normalized email is already registered
     */
    RegisteredUser register(String email, String password);
}
