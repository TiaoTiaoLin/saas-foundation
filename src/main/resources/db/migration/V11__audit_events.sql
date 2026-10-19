-- Append-only history of security, membership, organization, and billing actions.
-- Rows are not updated. The application role can insert and read them.
-- tenant_id is empty when the action is not tied to one organization.

CREATE TABLE audit_events (
    id             UUID         PRIMARY KEY,
    tenant_id      UUID,
    actor_user_id  UUID,
    action         VARCHAR(64)  NOT NULL,
    target_type    VARCHAR(32)  NOT NULL,
    target_id      UUID,
    metadata       JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_audit_events_action CHECK (action IN (
        'AUTH_LOGIN',
        'AUTH_LOGOUT',
        'AUTH_PASSWORD_CHANGED',
        'AUTH_PASSWORD_RESET_REQUESTED',
        'AUTH_PASSWORD_RESET_CONFIRMED',
        'AUTH_ACCOUNT_DELETED',
        'MEMBERSHIP_INVITED',
        'MEMBERSHIP_ACCEPTED',
        'MEMBERSHIP_ROLE_CHANGED',
        'MEMBERSHIP_REMOVED',
        'ORGANIZATION_LOGO_UPDATED',
        'ORGANIZATION_LOGO_REMOVED',
        'BILLING_SUBSCRIPTION_CHANGED'
    )),
    CONSTRAINT ck_audit_events_target_type CHECK (target_type IN (
        'USER',
        'ORGANIZATION',
        'SUBSCRIPTION'
    ))
);

CREATE INDEX idx_audit_events_tenant_created
    ON audit_events (tenant_id, created_at DESC);

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'saas_app') THEN
        GRANT SELECT, INSERT ON TABLE audit_events TO saas_app;
    END IF;
END
$$;
