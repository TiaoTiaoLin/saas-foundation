package com.saas.foundation.integration.email;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Test double for {@link EmailProviderClient}. Injects transient failures so Resilience4j
 * retry and circuit-breaker behavior can be exercised without calling Resend.
 */
@Slf4j
@Component
@Profile("test")
public class SimulatedEmailProviderClient implements EmailProviderClient {

    private final AtomicInteger attempts = new AtomicInteger();
    private final double failureRate;

    public SimulatedEmailProviderClient(
            @Value("${saas.integrations.email.simulated-failure-rate:0.0}") double failureRate) {
        this.failureRate = Math.clamp(failureRate, 0.0, 1.0);
    }

    @Override
    public EmailSendResult send(EmailMessage message) {
        int attempt = attempts.incrementAndGet();
        if (ThreadLocalRandom.current().nextDouble() < failureRate) {
            log.warn("Simulated email provider failure attempt={} to={}", attempt, message.to());
            throw new EmailProviderException("Upstream email provider unavailable (simulated)",
                    new IOException("connection reset"));
        }
        String messageId = "msg_" + UUID.randomUUID();
        log.info("Email accepted by provider messageId={} to={}", messageId, message.to());
        return EmailSendResult.ok(messageId);
    }

    int attemptCount() {
        return attempts.get();
    }
}
