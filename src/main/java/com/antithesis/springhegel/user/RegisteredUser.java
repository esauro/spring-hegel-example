package com.antithesis.springhegel.user;

/** Result of a successful registration — everything the outside world may know about a new user. */
public record RegisteredUser(Long id, String email) {
}
