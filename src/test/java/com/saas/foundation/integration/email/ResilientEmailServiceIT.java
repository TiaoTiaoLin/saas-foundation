package com.saas.foundation.integration.email;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.redis.testcontainers.RedisContainer;
import com.saas.foundation.support.PostgresRedisContainers;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class ResilientEmailServiceIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresRedisContainers.postgres();

    @Container
    static final RedisContainer REDIS = PostgresRedisContainers.redis();

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        PostgresRedisContainers.register(registry, POSTGRES, REDIS);
        registry.add("saas.integrations.email.simulated-failure-rate", () -> "1.0");
        registry.add("resilience4j.retry.instances.emailProvider.maxAttempts", () -> "3");
        registry.add("resilience4j.retry.instances.emailProvider.waitDuration", () -> "10ms");
    }

    @Autowired
    ResilientEmailService resilientEmailService;

    @Autowired
    SimulatedEmailProviderClient simulatedEmailProviderClient;

    @Test
    void fallsBackAfterRetriesWhenProviderAlwaysFails() {
        int before = simulatedEmailProviderClient.attemptCount();

        EmailSendResult result = resilientEmailService.sendWelcomeEmail(
                "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                "admin@example.com"
        );

        assertThat(result.success()).isFalse();
        assertThat(result.detail()).contains("queued-for-retry");
        // initial try + retries (maxAttempts=3 => 3 total calls through retry)
        assertThat(simulatedEmailProviderClient.attemptCount() - before).isGreaterThanOrEqualTo(3);
    }
}
