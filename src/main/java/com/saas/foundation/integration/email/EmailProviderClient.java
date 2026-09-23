package com.saas.foundation.integration.email;

/**
 * Outbound email provider abstraction (SendGrid / SES / etc.).
 */
public interface EmailProviderClient {

    EmailSendResult send(EmailMessage message);
}
