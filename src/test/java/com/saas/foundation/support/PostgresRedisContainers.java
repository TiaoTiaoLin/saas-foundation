package com.saas.foundation.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.redis.testcontainers.RedisContainer;

/**
 * Shared helpers for Postgres + Redis Testcontainers integration tests.
 */
public final class PostgresRedisContainers {

    private PostgresRedisContainers() {
    }

    public static PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("saas_foundation")
                .withUsername("test")
                .withPassword("test");
    }

    public static RedisContainer redis() {
        return new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    }

    public static void register(DynamicPropertyRegistry registry,
                                PostgreSQLContainer<?> postgres,
                                RedisContainer redis) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }
}
