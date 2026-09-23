package com.saas.foundation.integration.email;

public record EmailSendResult(boolean success, String providerMessageId, String detail) {

    public static EmailSendResult ok(String providerMessageId) {
        return new EmailSendResult(true, providerMessageId, "accepted");
    }

    public static EmailSendResult fallback(String reason) {
        return new EmailSendResult(false, null, reason);
    }
}
