package com.antithesis.springhegel.user;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Data-access contract for {@link Session}. Deliberately narrow (extends the bare Spring Data
 * marker rather than {@code JpaRepository}) so the domain only depends on what it uses and
 * tests can supply a trivial in-memory implementation.
 */
public interface SessionRepository extends Repository<Session, Long> {

    Session save(Session session);

    Optional<Session> findByToken(String token);

    void delete(Session session);

    /** Removes every session owned by {@code user}, whether live or expired. Used when the user is deleted. */
    void deleteAllByUser(User user);
}
