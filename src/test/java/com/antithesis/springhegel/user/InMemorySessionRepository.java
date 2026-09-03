package com.antithesis.springhegel.user;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Test double for {@link SessionRepository}: a map keyed by token that mirrors the database's
 * unique constraint. A fresh instance per Hegel draw keeps property tests free of shared state.
 */
final class InMemorySessionRepository implements SessionRepository {

    private final Map<String, Session> byToken = new HashMap<>();
    private long nextId = 1;

    @Override
    public Session save(Session session) {
        if (byToken.containsKey(session.getToken())) {
            throw new DataIntegrityViolationException("duplicate token");
        }
        ReflectionTestUtils.setField(session, "id", nextId++);
        byToken.put(session.getToken(), session);
        return session;
    }

    @Override
    public Optional<Session> findByToken(String token) {
        return Optional.ofNullable(byToken.get(token));
    }

    @Override
    public void delete(Session session) {
        byToken.remove(session.getToken());
    }

    int size() {
        return byToken.size();
    }

    boolean containsToken(String token) {
        return byToken.containsKey(token);
    }
}
