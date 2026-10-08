package com.saas.foundation.security;

import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {

    static final String BCRYPT_ID = "bcrypt";
    static final int BCRYPT_STRENGTH = 12;

    /**
     * Hashes are stored with an algorithm prefix (e.g. {@code {bcrypt}$2a$12$...}) so the
     * algorithm can be changed later without invalidating existing passwords. Only bcrypt
     * is registered; unlike {@code PasswordEncoderFactories}, this rejects {@code {noop}}
     * plaintext values instead of matching them.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new DelegatingPasswordEncoder(BCRYPT_ID,
                Map.of(BCRYPT_ID, new BCryptPasswordEncoder(BCRYPT_STRENGTH)));
    }
}
