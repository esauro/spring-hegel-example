package com.antithesis.springhegel.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A login session: the persistent record that one {@link User} is logged in through one opaque
 * token. The token is the only credential the browser holds, so it is never printed or logged.
 */
@Entity
@Table(name = "sessions")
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 43)
    private String token;

    @ManyToOne(optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    protected Session() {
        // required by JPA
    }

    public Session(String token, User user, Instant createdAt, Instant expiresAt) {
        this.token = token;
        this.user = user;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
    }

    public Long getId() {
        return id;
    }

    public String getToken() {
        return token;
    }

    public User getUser() {
        return user;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    /**
     * Whether this session has expired at {@code now}. The boundary is exclusive: a session is
     * expired from its {@code expiresAt} instant onwards, so {@code isExpiredAt(expiresAt)} is true.
     */
    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    @Override
    public String toString() {
        // token intentionally omitted
        return "Session[id=" + id + ", userId=" + user.getId() + ", createdAt=" + createdAt
                + ", expiresAt=" + expiresAt + "]";
    }
}
