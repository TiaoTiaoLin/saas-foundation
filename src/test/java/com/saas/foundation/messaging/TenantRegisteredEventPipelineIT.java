package com.saas.foundation.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.redis.testcontainers.RedisContainer;
import com.saas.foundation.messaging.event.TenantRegisteredEvent;
import com.saas.foundation.support.PostgresRedisContainers;

@SpringBootTest(properties = {
        "saas.kafka.enabled=true",
        "spring.kafka.listener.auto-startup=true"
})
@EmbeddedKafka(partitions = 1, topics = {"tenant.registered"})
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class TenantRegisteredEventPipelineIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresRedisContainers.postgres();

    @Container
    static final RedisContainer REDIS = PostgresRedisContainers.redis();

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        PostgresRedisContainers.register(registry, POSTGRES, REDIS);
        registry.add("saas.kafka.enabled", () -> "true");
        registry.add("spring.kafka.bootstrap-servers",
                () -> System.getProperty("spring.embedded.kafka.brokers"));
    }

    @Autowired
    TenantEventPublisher publisher;

    @Autowired
    TenantRegisteredEventListener listener;

    @Test
    void publishesAndConsumesTenantRegisteredEvent() throws Exception {
        long before = listener.processedCount();
        TenantRegisteredEvent event = TenantRegisteredEvent.of(
                "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                "Acme Corp",
                "ops@acme.test"
        );

        publisher.publishTenantRegistered(event).orTimeout(10, TimeUnit.SECONDS).join();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (listener.processedCount() <= before && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(listener.processedCount()).isGreaterThan(before);
    }
}
