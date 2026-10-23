package com.saas.foundation.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import javax.sql.DataSource;

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

/**
 * The README says the application role can add and read audit rows but not change or remove them.
 * This connects as that role over plain JDBC and checks the database enforces it.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class AuditAppendOnlyIT {

    /** PostgreSQL error code insufficient_privilege. */
    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresRedisContainers.postgres();

    @Container
    static final RedisContainer REDIS = PostgresRedisContainers.redis();

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        PostgresRedisContainers.register(registry, POSTGRES, REDIS);
        registry.add("saas.rate-limit.enabled", () -> "false");
        registry.add("saas.auth-rate-limit.enabled", () -> "false");
    }

    @Autowired
    DataSource dataSource;

    @Test
    void theApplicationRoleCanAddAndReadAuditRows() throws SQLException {
        UUID id = insertAuditRow();

        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement("SELECT action FROM audit_events WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString("action")).isEqualTo("AUTH_LOGIN");
            }
        }
    }

    @Test
    void theApplicationRoleCannotUpdateAnAuditRow() throws SQLException {
        UUID id = insertAuditRow();

        assertThatThrownBy(() -> execute("UPDATE audit_events SET action = 'AUTH_LOGOUT' WHERE id = '" + id + "'"))
                .isInstanceOfSatisfying(SQLException.class,
                        ex -> assertThat(ex.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
    }

    @Test
    void theApplicationRoleCannotDeleteAnAuditRow() throws SQLException {
        UUID id = insertAuditRow();

        assertThatThrownBy(() -> execute("DELETE FROM audit_events WHERE id = '" + id + "'"))
                .isInstanceOfSatisfying(SQLException.class,
                        ex -> assertThat(ex.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
    }

    @Test
    void theApplicationRoleCannotTruncateTheAuditLog() {
        assertThatThrownBy(() -> execute("TRUNCATE audit_events"))
                .isInstanceOfSatisfying(SQLException.class,
                        ex -> assertThat(ex.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
    }

    private UUID insertAuditRow() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "INSERT INTO audit_events (id, tenant_id, actor_user_id, action, target_type, target_id, metadata, created_at) "
                             + "VALUES (?, ?, ?, 'AUTH_LOGIN', 'USER', ?, '{}'::jsonb, now())")) {
            ps.setObject(1, id);
            ps.setObject(2, UUID.randomUUID());
            ps.setObject(3, UUID.randomUUID());
            ps.setObject(4, UUID.randomUUID());
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        return id;
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
