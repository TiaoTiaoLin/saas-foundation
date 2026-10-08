-- Tenants registry. Like users and tenant_memberships, this table has no row-level
-- security: tenants are looked up by id to validate requests before a tenant
-- context exists, and the table holds no tenant-private business data.

CREATE TABLE tenants (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(128) NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT ck_tenants_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

-- products.tenant_id does not reference tenants yet: requests may still use
-- arbitrary tenant ids via the X-Tenant-ID header until registration creates tenants.
ALTER TABLE tenant_memberships
    ADD CONSTRAINT fk_tenant_memberships_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE;

-- Refresh tokens are opaque random values; only their SHA-256 hash (hex) is stored.
-- Each token belongs to one membership, so removing a user from a tenant also
-- removes their refresh tokens for that tenant.
CREATE TABLE refresh_tokens (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL,
    user_id      UUID        NOT NULL,
    token_hash   CHAR(64)    NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,
    replaced_by  UUID        REFERENCES refresh_tokens (id) ON DELETE SET NULL,
    created_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_membership
        FOREIGN KEY (tenant_id, user_id)
        REFERENCES tenant_memberships (tenant_id, user_id) ON DELETE CASCADE
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);

-- Application role privileges (role created in docker init script; noop-safe for CI)
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'saas_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE tenants, refresh_tokens TO saas_app;
    END IF;
END
$$;
