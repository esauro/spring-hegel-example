package com.antithesis.springhegel.user.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** HTTP wiring checks for login/logout: status codes, cookie attributes, JSON contract and the static page. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SessionApiTest {

    private static final String PASSWORD = "Str0ng!pass";

    @Autowired
    private MockMvcTester mvc;

    @Test
    void loginReturns201TheUserAndAnHttpOnlyCookie() {
        register("login-alice@example.com");

        MvcTestResult result = login("  Login-Alice@Example.COM ", PASSWORD);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.id").isNotNull();
        assertThat(result).bodyJson().extractingPath("$.email").isEqualTo("login-alice@example.com");
        assertThat(result).bodyText().doesNotContain("token");
        Cookie cookie = sessionCookie(result);
        assertThat(cookie).isNotNull();
        assertThat(cookie.getValue()).matches("[A-Za-z0-9_-]{43}");
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/");
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).contains("SameSite=Strict").doesNotContain("Max-Age");
    }

    @Test
    void currentSessionReturnsTheUserWhenTheCookieIsValid() {
        register("login-bob@example.com");
        Cookie cookie = sessionCookie(login("login-bob@example.com", PASSWORD));

        MvcTestResult result = mvc.get().uri("/api/session").cookie(cookie).exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyJson().extractingPath("$.email").isEqualTo("login-bob@example.com");
        assertThat(result).bodyText().doesNotContain(cookie.getValue());
    }

    @Test
    void currentSessionReturns204WithoutACookieOrWithAForgedOne() {
        MvcTestResult withoutCookie = mvc.get().uri("/api/session").exchange();
        assertThat(withoutCookie).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(withoutCookie).bodyText().isEmpty();

        MvcTestResult forged = mvc.get().uri("/api/session")
                .cookie(new Cookie(SessionController.SESSION_COOKIE, "forged"))
                .exchange();
        assertThat(forged).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(forged).bodyText().isEmpty();
    }

    @Test
    void logoutClearsTheCookieAndInvalidatesTheSession() {
        register("login-carol@example.com");
        Cookie cookie = sessionCookie(login("login-carol@example.com", PASSWORD));

        MvcTestResult logout = mvc.delete().uri("/api/session").cookie(cookie).exchange();

        assertThat(logout).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(logout.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .startsWith("SESSION=;").contains("Max-Age=0").contains("HttpOnly");
        assertThat(mvc.get().uri("/api/session").cookie(cookie).exchange()).hasStatus(HttpStatus.NO_CONTENT);
    }

    @Test
    void logoutWithoutASessionIsIdempotent() {
        MvcTestResult result = mvc.delete().uri("/api/session").exchange();

        assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
    }

    @Test
    void wrongPasswordReturns401InvalidCredentials() throws Exception {
        register("login-dave@example.com");

        MvcTestResult wrongPassword = login("login-dave@example.com", "Wr0ng!pass");
        MvcTestResult unknownEmail = login("nobody-here@example.com", PASSWORD);

        for (MvcTestResult result : new MvcTestResult[] {wrongPassword, unknownEmail}) {
            assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("INVALID_CREDENTIALS");
            assertThat(result).bodyJson().extractingPath("$.messages").asArray()
                    .containsExactly("Invalid email or password");
            assertThat(sessionCookie(result)).isNull();
        }
        assertThat(wrongPassword.getResponse().getContentAsString())
                .isEqualTo(unknownEmail.getResponse().getContentAsString());
    }

    @Test
    void blankCredentialsReturn400ValidationError() {
        MvcTestResult result = login(" ", " ");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_ERROR");
        assertThat(result).bodyJson().extractingPath("$.messages").asArray()
                .containsExactly("Email must not be blank", "Password must not be blank");
    }

    @Test
    void loginWhileLoggedInRotatesTheCookie() {
        register("login-erin@example.com");
        Cookie first = sessionCookie(login("login-erin@example.com", PASSWORD));

        MvcTestResult second = mvc.post().uri("/api/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody("login-erin@example.com", PASSWORD))
                .cookie(first)
                .exchange();

        assertThat(second).hasStatus(HttpStatus.CREATED);
        Cookie rotated = sessionCookie(second);
        assertThat(rotated.getValue()).isNotEqualTo(first.getValue());
        assertThat(mvc.get().uri("/api/session").cookie(first).exchange()).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(mvc.get().uri("/api/session").cookie(rotated).exchange()).hasStatus(HttpStatus.OK);
    }

    @Test
    void loginPageIsServed() {
        assertThat(mvc.get().uri("/login.html").exchange()).hasStatus(HttpStatus.OK);
    }

    private void register(String email) {
        MvcTestResult result = mvc.post().uri("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(email, PASSWORD))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
    }

    private MvcTestResult login(String email, String password) {
        return mvc.post().uri("/api/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(loginBody(email, password))
                .exchange();
    }

    private static String loginBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static Cookie sessionCookie(MvcTestResult result) {
        return result.getResponse().getCookie(SessionController.SESSION_COOKIE);
    }
}
