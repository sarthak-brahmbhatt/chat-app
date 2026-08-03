package com.chatapp.userservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Registers a single PasswordEncoder bean that UserService (and later, the
 * login endpoint) can have injected via constructor injection.
 *
 * BCrypt is the standard choice for password hashing: unlike a plain hash
 * (e.g. SHA-256), it's deliberately slow and has a per-password random salt
 * baked into its own output, which is what makes it resistant to rainbow-table
 * and brute-force attacks. We never see or store the plaintext password once
 * this has run.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
