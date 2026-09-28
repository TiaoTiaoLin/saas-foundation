package com.saas.foundation.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.redis.testcontainers.RedisContainer;

/**
 * Shared helpers for Postgres + Redis Testcontainers integration tests.
 *
 * <p>Mirrors the docker-compose setup: Flyway migrates as the container superuser,
 * while the application connects as {@code saas_app}. Superusers bypass row-level
 * security, so the app must not use the superuser or RLS policies go untested.
 */
public final class PostgresRedisContainers {

    public static final String APP_USERNAME = "saas_app";
    public static final String APP_PASSWORD = "saas_app";

    private PostgresRedisContainers() {
    }

    public static PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
                .withDatabaseName("saas_foundation")
                .withUsername("test")
                .withPassword("test")
                .withCopyFileToContainer(
                        MountableFile.forHostPath("docker/postgres/init-rls-role.sql"),
                        "/docker-entrypoint-initdb.d/01-init-rls-role.sql");
    }

    public static RedisContainer redis() {
        return new RedisContainer(DockerImageName.parse("redis:7-alpine"));
    }

    public static void register(DynamicPropertyRegistry registry,
                                PostgreSQLContainer<?> postgres,
                                RedisContainer redis) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USERNAME);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }
}
