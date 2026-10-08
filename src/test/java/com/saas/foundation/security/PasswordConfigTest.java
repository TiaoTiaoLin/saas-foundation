package com.saas.foundation.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordConfigTest {

    private final PasswordEncoder encoder = new PasswordConfig().passwordEncoder();

    @Test
    void hashesWithBcryptPrefixAndConfiguredStrength() {
        String hash = encoder.encode("correct horse battery staple");

        assertThat(hash).startsWith("{bcrypt}$2a$" + PasswordConfig.BCRYPT_STRENGTH + "$");
        assertThat(hash).hasSizeLessThanOrEqualTo(255);
    }

    @Test
    void matchesOnlyTheOriginalPassword() {
        String hash = encoder.encode("correct horse battery staple");

        assertThat(encoder.matches("correct horse battery staple", hash)).isTrue();
        assertThat(encoder.matches("wrong password", hash)).isFalse();
    }

    @Test
    void samePasswordProducesDifferentHashes() {
        assertThat(encoder.encode("same")).isNotEqualTo(encoder.encode("same"));
    }

    @Test
    void plaintextNoopValuesAreRejected() {
        assertThatThrownBy(() -> encoder.matches("secret", "{noop}secret"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
