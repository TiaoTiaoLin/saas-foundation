package com.saas.foundation.integration.email;

public class EmailProviderException extends RuntimeException {

    public EmailProviderException(String message) {
        super(message);
    }

    public EmailProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
