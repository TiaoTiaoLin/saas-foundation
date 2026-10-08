-- Users are global (one login can belong to several tenants); tenant access is
-- granted through tenant_memberships.
--
-- Row-level security is intentionally NOT enabled on these tables: login looks up
-- a user by email and lists their memberships before any tenant is known, so a
-- policy keyed on app.current_tenant would hide the rows that login needs.

CREATE TABLE users (
    id             UUID         PRIMARY KEY,
    email          VARCHAR(320) NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    enabled        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    version        BIGINT       NOT NULL DEFAULT 0
);

-- Emails are unique regardless of letter case.
CREATE UNIQUE INDEX uk_users_email_lower ON users (lower(email));

CREATE TABLE tenant_memberships (
    id          UUID        PRIMARY KEY,
    tenant_id   UUID        NOT NULL,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role        VARCHAR(32) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL,
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_tenant_memberships_tenant_user UNIQUE (tenant_id, user_id),
    CONSTRAINT ck_tenant_memberships_role CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER'))
);

CREATE INDEX idx_tenant_memberships_user_id ON tenant_memberships (user_id);

-- Application role privileges (role created in docker init script; noop-safe for CI)
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'saas_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE users, tenant_memberships TO saas_app;
    END IF;
END
$$;
