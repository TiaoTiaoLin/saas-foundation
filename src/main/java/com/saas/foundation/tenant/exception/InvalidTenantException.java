package com.saas.foundation.tenant.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidTenantException extends RuntimeException {

    public InvalidTenantException(String tenantId) {
        super("Invalid tenant identifier: " + tenantId);
    }

    public InvalidTenantException(String message, Throwable cause) {
        super(message, cause);
    }
}
