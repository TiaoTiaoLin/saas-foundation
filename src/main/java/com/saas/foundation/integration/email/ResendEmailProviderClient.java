package com.saas.foundation.integration.email;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.resend.Resend;
import com.resend.core.exception.ResendException;
import com.resend.services.emails.model.CreateEmailOptions;
import com.resend.services.emails.model.CreateEmailResponse;

import lombok.extern.slf4j.Slf4j;

/**
 * Sends mail through the Resend API. Transient failures are thrown so Resilience4j can retry them.
 * A rejected request (most 4xx responses) is returned as a failed result and is not retried.
 */
@Slf4j
@Component
@Profile("!test")
public class ResendEmailProviderClient implements EmailProviderClient {

    private final Resend resend;
    private final String from;

    @Autowired
    public ResendEmailProviderClient(EmailProperties properties) {
        this(client(properties), properties.from());
    }

    ResendEmailProviderClient(Resend resend, String from) {
        if (from == null || from.isBlank()) {
            throw new IllegalStateException("Set RESEND_FROM");
        }
        this.resend = resend;
        this.from = from;
    }

    @Override
    public EmailSendResult send(EmailMessage message) {
        CreateEmailOptions options = CreateEmailOptions.builder()
                .from(from)
                .to(message.to())
                .subject(message.subject())
                .text(message.body())
                .build();
        try {
            CreateEmailResponse response = resend.emails().send(options);
            String messageId = response.getId();
            if (messageId == null || messageId.isBlank()) {
                throw new EmailProviderException("Resend accepted the email without a message id");
            }
            log.info("Email accepted by Resend messageId={} to={}", messageId, message.to());
            return EmailSendResult.ok(messageId);
        } catch (ResendException ex) {
            if (retryable(ex)) {
                log.warn("Resend send failed status={} to={}", ex.getStatusCode(), message.to());
                throw new EmailProviderException("Resend email send failed", ex);
            }
            log.warn("Resend rejected email status={} to={}", ex.getStatusCode(), message.to());
            return EmailSendResult.fallback("rejected");
        }
    }

    private static Resend client(EmailProperties properties) {
        String apiKey = properties.apiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Set RESEND_API_KEY");
        }
        return new Resend(apiKey);
    }

    /** Network failures, timeouts, rate limits, and 5xx responses are worth retrying. */
    private static boolean retryable(ResendException ex) {
        Integer status = ex.getStatusCode();
        if (status == null) {
            return true;
        }
        return status == 408 || status == 429 || status >= 500;
    }
}
