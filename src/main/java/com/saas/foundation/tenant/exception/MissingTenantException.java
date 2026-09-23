package com.saas.foundation.tenant.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class MissingTenantException extends RuntimeException {

    public MissingTenantException() {
        super("Missing required tenant header");
    }

    public MissingTenantException(String message) {
        super(message);
    }
}
