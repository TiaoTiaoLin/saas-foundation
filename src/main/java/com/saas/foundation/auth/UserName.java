package com.saas.foundation.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Display name stored on the user and shared by every organization they belong to. */
public final class UserName {

    public static final int MAX_LENGTH = 80;
    public static final String REQUIRED = "Name is required";
    public static final String TOO_LONG = "Name must be at most " + MAX_LENGTH + " characters";

    private UserName() {
    }

    public static String normalize(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, REQUIRED);
        }
        if (trimmed.length() > MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, TOO_LONG);
        }
        return trimmed;
    }
}
