package com.antithesis.springhegel.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {

    /**
     * Strength defaults to BCrypt's 10; the {@code test} profile lowers it so property tests that
     * drive the real application can run many iterations quickly.
     */
    @Bean
    public PasswordEncoder passwordEncoder(@Value("${app.password.bcrypt-strength:10}") int strength) {
        return new BCryptPasswordEncoder(strength);
    }
}
