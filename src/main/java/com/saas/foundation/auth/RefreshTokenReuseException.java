package com.saas.foundation.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * An already-rotated refresh token was presented again, which suggests it was stolen.
 * Transactions must not roll back on this exception, so that the session revocation it
 * triggers is committed.
 */
public class RefreshTokenReuseException extends ResponseStatusException {

    public RefreshTokenReuseException() {
        super(HttpStatus.UNAUTHORIZED, RefreshTokenService.INVALID_REFRESH_TOKEN);
    }
}
