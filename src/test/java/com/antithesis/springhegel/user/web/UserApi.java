package com.antithesis.springhegel.user.web;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.http.Cookie;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * The user API as seen from a test: one method per HTTP operation, returning the raw
 * {@link MvcTestResult} so each property asserts exactly the contract it cares about. Shared by
 * the two full-stack lifecycle properties. A {@code null} cookie means "send no cookie".
 */
final class UserApi {

    static final Pattern TOKEN_SHAPE = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern ID = Pattern.compile("\"id\":(\\d+)");

    private final MockMvcTester mvc;

    UserApi(MockMvcTester mvc) {
        this.mvc = mvc;
    }

    MvcTestResult register(String email, String password) {
        return mvc.post().uri("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email, password))
                .exchange();
    }

    MvcTestResult login(String email, String password) {
        return mvc.post().uri("/api/session")
                .contentType(MediaType.APPLICATION_JSON)
                .content(credentials(email, password))
                .exchange();
    }

    MvcTestResult session(Cookie cookie) {
        return withCookie(mvc.get().uri("/api/session"), cookie).exchange();
    }

    MvcTestResult logout(Cookie cookie) {
        return withCookie(mvc.delete().uri("/api/session"), cookie).exchange();
    }

    MvcTestResult deleteMe(Cookie cookie) {
        return withCookie(mvc.delete().uri("/api/users/me"), cookie).exchange();
    }

    private static MockMvcTester.MockMvcRequestBuilder withCookie(
            MockMvcTester.MockMvcRequestBuilder request, Cookie cookie) {
        return cookie == null ? request : request.cookie(cookie);
    }

    static Cookie sessionCookie(MvcTestResult result) {
        Cookie cookie = result.getResponse().getCookie(SessionController.SESSION_COOKIE);
        assertThat(cookie).isNotNull();
        return cookie;
    }

    static long idOf(MvcTestResult result) {
        Matcher matcher = ID.matcher(body(result));
        assertThat(matcher.find()).as("id in %s", body(result)).isTrue();
        return Long.parseLong(matcher.group(1));
    }

    private static String credentials(String email, String password) {
        return "{\"email\":\"" + json(email) + "\",\"password\":\"" + json(password) + "\"}";
    }

    /** Valid passwords contain backslashes and double quotes; both must be escaped inside a JSON string. */
    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String body(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (UnsupportedEncodingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
