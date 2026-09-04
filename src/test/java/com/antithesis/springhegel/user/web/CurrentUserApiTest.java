package com.antithesis.springhegel.user.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** HTTP wiring checks for account deletion that have no input space: the 401 paths and the page control. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CurrentUserApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Test
    void deleteWithoutACookieReturns401NotLoggedInAndClearsTheCookie() {
        MvcTestResult result = mvc.delete().uri("/api/users/me").exchange();

        assertNotLoggedIn(result);
    }

    @Test
    void deleteWithAForgedCookieReturns401NotLoggedIn() {
        MvcTestResult result = mvc.delete().uri("/api/users/me")
                .cookie(new Cookie(SessionController.SESSION_COOKIE, "forged"))
                .exchange();

        assertNotLoggedIn(result);
    }

    @Test
    void loginPageOffersAccountDeletion() {
        MvcTestResult result = mvc.get().uri("/login.html").exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(result).bodyText().contains("id=\"delete-button\"").contains("/api/users/me");
    }

    private static void assertNotLoggedIn(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("NOT_LOGGED_IN");
        assertThat(result).bodyJson().extractingPath("$.messages").asArray().containsExactly("Not logged in");
        assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE))
                .startsWith("SESSION=;").contains("Max-Age=0").contains("HttpOnly");
    }
}
