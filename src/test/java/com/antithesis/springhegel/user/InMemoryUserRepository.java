package com.antithesis.springhegel.user;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Test double for {@link UserRepository}: a map keyed by email that mirrors the database's
 * unique constraint. A fresh instance per Hegel draw keeps property tests free of shared state.
 */
final class InMemoryUserRepository implements UserRepository {

    private final Map<String, User> byEmail = new HashMap<>();
    private long nextId = 1;

    @Override
    public User save(User user) {
        if (byEmail.containsKey(user.getEmail())) {
            throw new DataIntegrityViolationException("duplicate email: " + user.getEmail());
        }
        ReflectionTestUtils.setField(user, "id", nextId++);
        byEmail.put(user.getEmail(), user);
        return user;
    }

    @Override
    public boolean existsByEmail(String email) {
        return byEmail.containsKey(email);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return Optional.ofNullable(byEmail.get(email));
    }

    int size() {
        return byEmail.size();
    }
}
