package com.saas.foundation.integration.email;

/**
 * Outbound email provider. The application implementation sends through Resend.
 */
public interface EmailProviderClient {

    EmailSendResult send(EmailMessage message);
}
