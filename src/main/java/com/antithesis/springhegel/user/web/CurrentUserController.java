package com.antithesis.springhegel.user.web;

import com.antithesis.springhegel.user.UserService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP + cookie adapter for deleting the logged-in user. The subject is always the owner of the
 * live session presented in the {@code SESSION} cookie: there is no id in the path by design (no
 * enumeration, no authorization check to get wrong). Deletion needs no password re-confirmation —
 * a deliberate showcase trade-off; a production deployment should add re-authentication.
 */
@RestController
@RequestMapping("/api/users/me")
public class CurrentUserController {

    private final UserService userService;

    public CurrentUserController(UserService userService) {
        this.userService = userService;
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteCurrentUser(
            @CookieValue(name = SessionController.SESSION_COOKIE, required = false) String token) {
        userService.deleteCurrentUser(token);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, SessionController.clearedSessionCookie())
                .build();
    }
}
