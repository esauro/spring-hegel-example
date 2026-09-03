package com.antithesis.springhegel.user;

import java.util.Optional;

/**
 * Business contract for registering users and managing their login sessions.
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

    /**
     * Logs a registered user in and opens a fresh session.
     *
     * <p>{@code email} and {@code password} may be {@code null} or padded; they are normalized
     * exactly like in {@link #register} (email trimmed and lowercased, password stripped).
     * {@code replacedToken} may be {@code null}; when it identifies an existing session, that
     * session is deleted <em>after</em> successful authentication (session rotation). The
     * returned session is valid for {@link UserServiceImpl#SESSION_LIFETIME}.
     *
     * @throws InvalidLoginException if the normalized email or the trimmed password is blank
     * @throws InvalidCredentialsException if the email is unknown or the password does not match
     *     (the two cases are indistinguishable)
     */
    ActiveSession login(String email, String password, String replacedToken);

    /**
     * Ends the session identified by {@code token}. {@code null}, unknown, expired or already
     * ended tokens are silently ignored, so the operation is idempotent and never throws.
     */
    void logout(String token);

    /**
     * Resolves {@code token} to the logged-in user. Returns empty for {@code null}, unknown or
     * expired tokens; an expired session encountered here is deleted. Never throws.
     */
    Optional<RegisteredUser> currentUser(String token);
}
