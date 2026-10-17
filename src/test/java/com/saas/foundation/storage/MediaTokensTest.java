package com.saas.foundation.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;

class MediaTokensTest {

    private static final String SECRET = "local-dev-only-media-signing-secret-32b";
    private static final String KEY = "avatars/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222.jpg";

    @Test
    void aSignedTokenRoundTrips() {
        long expires = Instant.now().plusSeconds(60).getEpochSecond();
        String signature = MediaTokens.sign(KEY, expires, SECRET);

        assertThat(MediaTokens.valid(KEY, expires, signature, SECRET)).isTrue();
    }

    @Test
    void anExpiredTokenIsRejected() {
        long expires = Instant.now().minusSeconds(5).getEpochSecond();
        String signature = MediaTokens.sign(KEY, expires, SECRET);

        assertThat(MediaTokens.valid(KEY, expires, signature, SECRET)).isFalse();
    }

    @Test
    void aTamperedSignatureIsRejected() {
        long expires = Instant.now().plusSeconds(60).getEpochSecond();
        String signature = MediaTokens.sign(KEY, expires, SECRET);

        assertThat(MediaTokens.valid(KEY, expires, signature + "x", SECRET)).isFalse();
        assertThat(MediaTokens.valid(KEY + "x", expires, signature, SECRET)).isFalse();
    }
}
