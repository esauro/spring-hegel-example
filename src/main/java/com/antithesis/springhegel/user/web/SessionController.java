package com.antithesis.springhegel.user.web;

import com.antithesis.springhegel.user.ActiveSession;
import com.antithesis.springhegel.user.RegisteredUser;
import com.antithesis.springhegel.user.UserService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP + cookie adapter for login, logout and the current-session query. The session token
 * travels only in the {@code SESSION} cookie, never in a response body.
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    static final String SESSION_COOKIE = "SESSION";

    private final UserService userService;

    public SessionController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping
    public ResponseEntity<RegisteredUser> login(
            @RequestBody LoginRequest request,
            @CookieValue(name = SESSION_COOKIE, required = false) String presentedToken) {
        ActiveSession session = userService.login(request.email(), request.password(), presentedToken);
        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.SET_COOKIE, sessionCookie(session.token()).build().toString())
                .body(session.user());
    }

    @DeleteMapping
    public ResponseEntity<Void> logout(@CookieValue(name = SESSION_COOKIE, required = false) String token) {
        userService.logout(token);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, clearedSessionCookie())
                .build();
    }

    @GetMapping
    public ResponseEntity<RegisteredUser> current(
            @CookieValue(name = SESSION_COOKIE, required = false) String token) {
        return userService.currentUser(token)
                .map(user -> ResponseEntity.ok(user))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * Header value that makes the browser drop the session cookie; shared by logout, account
     * deletion and the not-logged-in error.
     */
    static String clearedSessionCookie() {
        return sessionCookie("").maxAge(0).build().toString();
    }

    private static ResponseCookie.ResponseCookieBuilder sessionCookie(String value) {
        // No Max-Age: a browser-session cookie; the server-side lifetime is the real limit.
        // Secure is intentionally omitted because this showcase runs on plain http://localhost;
        // a production deployment behind HTTPS should add .secure(true).
        return ResponseCookie.from(SESSION_COOKIE, value)
                .httpOnly(true)
                .sameSite("Strict")
                .path("/");
    }
}
