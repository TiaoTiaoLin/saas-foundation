-- Password reset tokens are looked up before a tenant is known, so this table has
-- no row-level security. Only the SHA-256 hash of the token is stored.

CREATE TABLE password_reset_tokens (
    id          UUID        PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash  CHAR(64)    NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_password_reset_tokens_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_password_reset_tokens_user_id ON password_reset_tokens (user_id);

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'saas_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE password_reset_tokens TO saas_app;
    END IF;
END
$$;
