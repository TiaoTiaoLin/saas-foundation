package com.saas.foundation.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC that lets an image tag load a local file without an access token. */
public final class MediaTokens {

    private MediaTokens() {
    }

    public static String sign(String key, long expiresAtEpochSecond, String secret) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac(key, expiresAtEpochSecond, secret));
    }

    public static boolean valid(String key, long expiresAtEpochSecond, String signature, String secret) {
        if (key == null || signature == null || expiresAtEpochSecond < Instant.now().getEpochSecond()) {
            return false;
        }
        byte[] expected = mac(key, expiresAtEpochSecond, secret);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(signature);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] mac(String key, long expiresAtEpochSecond, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal((key + "\n" + expiresAtEpochSecond).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not sign a media URL", ex);
        }
    }
}
