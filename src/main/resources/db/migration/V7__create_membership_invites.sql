-- An invitation is not a membership. The person joins only after they accept the
-- link. Only the SHA-256 hash of that link's token is stored. Lookup happens
-- before the invited tenant is the caller's current tenant, so this table has
-- no row-level security.

CREATE TABLE membership_invites (
    id           UUID        PRIMARY KEY,
    tenant_id    UUID        NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role         VARCHAR(32) NOT NULL,
    token_hash   CHAR(64)    NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    accepted_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_membership_invites_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_membership_invites_role CHECK (role IN ('OWNER', 'ADMIN', 'MEMBER'))
);

-- One open invitation per person per organization. Accepted rows stay as history.
CREATE UNIQUE INDEX uk_membership_invites_pending
    ON membership_invites (tenant_id, user_id)
    WHERE accepted_at IS NULL;

CREATE INDEX idx_membership_invites_user_id ON membership_invites (user_id);

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'saas_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE membership_invites TO saas_app;
    END IF;
END
$$;
