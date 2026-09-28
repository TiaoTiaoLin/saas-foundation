package com.saas.foundation.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import com.saas.foundation.tenant.rls.TenantAwareJpaTransactionManager;

/**
 * Exercises the PostgreSQL policies on {@code products} over plain JDBC, bypassing
 * Hibernate's {@code @TenantId} and {@code tenantFilter}, so a broken policy
 * cannot be masked by the application-level tenant checks.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class ProductRowLevelSecurityIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = PostgresRedisContainers.postgres();

    @Container
    static final RedisContainer REDIS = PostgresRedisContainers.redis();

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        PostgresRedisContainers.register(registry, POSTGRES, REDIS);
        registry.add("saas.rate-limit.enabled", () -> "false");
    }

    @Autowired
    DataSource dataSource;

    @Test
    void applicationConnectsAsRoleSubjectToRls() throws SQLException {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement ps = connection.prepareStatement(
                     "SELECT rolsuper, rolbypassrls FROM pg_roles WHERE rolname = current_user");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getBoolean("rolsuper")).isFalse();
            assertThat(rs.getBoolean("rolbypassrls")).isFalse();
        }
    }

    @Test
    void tenantOnlySeesItsOwnRows() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();

        inTenantTransaction(tenantA, c -> insertProduct(c, tenantA, "A-1"));
        inTenantTransaction(tenantB, c -> insertProduct(c, tenantB, "B-1"));

        int visibleToA = inTenantTransaction(tenantA, ProductRowLevelSecurityIT::countProducts);
        int tenantBRowsVisibleToA = inTenantTransaction(tenantA, c -> countProductsFor(c, tenantB));
        int visibleToB = inTenantTransaction(tenantB, ProductRowLevelSecurityIT::countProducts);

        assertThat(visibleToA).isEqualTo(1);
        assertThat(tenantBRowsVisibleToA).isZero();
        assertThat(visibleToB).isEqualTo(1);
    }

    @Test
    void noRowsVisibleWithoutTenantContext() throws SQLException {
        UUID tenant = UUID.randomUUID();
        inTenantTransaction(tenant, c -> insertProduct(c, tenant, "X-1"));

        int visibleWithoutTenant = inTenantTransaction(null, ProductRowLevelSecurityIT::countProducts);

        assertThat(visibleWithoutTenant).isZero();
    }

    @Test
    void insertForAnotherTenantIsRejected() {
        UUID activeTenant = UUID.randomUUID();
        UUID otherTenant = UUID.randomUUID();

        assertThatThrownBy(() -> inTenantTransaction(activeTenant, c -> insertProduct(c, otherTenant, "EVIL-1")))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("row-level security");
    }

    @Test
    void updateCannotReachAnotherTenantsRows() throws SQLException {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        inTenantTransaction(tenantA, c -> insertProduct(c, tenantA, "A-UPD"));

        int updated = inTenantTransaction(tenantB, c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE products SET name = 'hijacked' WHERE tenant_id = ?")) {
                ps.setObject(1, tenantA);
                return ps.executeUpdate();
            }
        });

        assertThat(updated).isZero();
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T apply(Connection connection) throws SQLException;
    }

    private <T> T inTenantTransaction(UUID tenantId, SqlWork<T> work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (tenantId != null) {
                    try (PreparedStatement ps = connection.prepareStatement("SELECT set_config(?, ?, true)")) {
                        ps.setString(1, TenantAwareJpaTransactionManager.TENANT_GUC);
                        ps.setString(2, tenantId.toString());
                        ps.execute();
                    }
                }
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    private static int insertProduct(Connection connection, UUID tenantId, String sku) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO products (id, tenant_id, name, sku, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """)) {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenantId);
            ps.setString(3, "Product " + sku);
            ps.setString(4, sku);
            return ps.executeUpdate();
        }
    }

    private static int countProducts(Connection connection) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT count(*) FROM products");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int countProductsFor(Connection connection, UUID tenantId) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT count(*) FROM products WHERE tenant_id = ?")) {
            ps.setObject(1, tenantId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}
