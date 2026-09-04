package com.antithesis.springhegel.user;

import java.util.Optional;
import org.springframework.data.repository.Repository;

/**
 * Data-access contract for {@link User}. Deliberately narrow (extends the bare Spring Data
 * marker rather than {@code JpaRepository}) so the domain only depends on what it uses and
 * tests can supply a trivial in-memory implementation.
 */
public interface UserRepository extends Repository<User, Long> {

    User save(User user);

    boolean existsByEmail(String email);

    Optional<User> findByEmail(String email);

    /**
     * Removes the user. Callers must delete the user's sessions first — {@code sessions.user_id}
     * is a not-null foreign key.
     */
    void delete(User user);
}
