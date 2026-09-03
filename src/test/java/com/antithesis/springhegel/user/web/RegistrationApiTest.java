package com.antithesis.springhegel.user.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.antithesis.springhegel.user.User;
import com.antithesis.springhegel.user.UserRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** HTTP wiring checks: status codes, JSON contract, persistence round-trip and the static page. */
@SpringBootTest
@AutoConfigureMockMvc
class RegistrationApiTest {

    @Autowired
    private MockMvcTester mvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void validRegistrationReturns201WithTheNormalizedUser() {
        MvcTestResult result = register("  Alice@Example.COM ", "Str0ng!pass");

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(result).bodyJson().extractingPath("$.id").isNotNull();
        assertThat(result).bodyJson().extractingPath("$.email").isEqualTo("alice@example.com");
    }

    @Test
    void registeredUserCanBeReadBackWithAMatchingHash() {
        assertThat(register("bob@example.com", "Str0ng!pass")).hasStatus(HttpStatus.CREATED);

        Optional<User> stored = userRepository.findByEmail("bob@example.com");

        assertThat(stored).isPresent();
        assertThat(stored.get().getId()).isNotNull();
        assertThat(passwordEncoder.matches("Str0ng!pass", stored.get().getPasswordHash())).isTrue();
    }

    @Test
    void duplicateEmailReturns409Conflict() {
        assertThat(register("carol@example.com", "Str0ng!pass")).hasStatus(HttpStatus.CREATED);

        MvcTestResult second = register("Carol@Example.com", "0therStr0ng!");

        assertThat(second).hasStatus(HttpStatus.CONFLICT);
        assertThat(second).bodyJson().extractingPath("$.code").isEqualTo("EMAIL_ALREADY_REGISTERED");
        assertThat(second).bodyJson().extractingPath("$.messages").asArray()
                .containsExactly("Email is already registered");
    }

    @Test
    void invalidPasswordReturns400ValidationError() {
        MvcTestResult result = register("dave@example.com", "weak");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_ERROR");
        assertThat(result).bodyJson().extractingPath("$.messages").asArray().isNotEmpty();
    }

    @Test
    void malformedBodyReturns400ValidationError() {
        MvcTestResult result = mvc.post().uri("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{not json")
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void registrationPageIsServed() {
        assertThat(mvc.get().uri("/register.html").exchange()).hasStatus(HttpStatus.OK);
    }

    private MvcTestResult register(String email, String password) {
        return mvc.post().uri("/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}")
                .exchange();
    }
}
