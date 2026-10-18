-- Stripe customer and subscription for the organization. NONE means the organization
-- has never subscribed, and product access stays open. A canceled or past-due
-- subscription blocks product routes. The organization can still sign in and open Billing.

ALTER TABLE tenants
    ADD COLUMN stripe_customer_id VARCHAR(255),
    ADD COLUMN stripe_subscription_id VARCHAR(255),
    ADD COLUMN subscription_status VARCHAR(32) NOT NULL DEFAULT 'NONE';

ALTER TABLE tenants
    ADD CONSTRAINT ck_tenants_subscription_status
        CHECK (subscription_status IN ('NONE', 'ACTIVE', 'PAST_DUE', 'CANCELED', 'INCOMPLETE'));

CREATE UNIQUE INDEX uk_tenants_stripe_customer
    ON tenants (stripe_customer_id);

-- Webhook deliveries are retried. The event id is stored only after the tenant update commits.
CREATE TABLE billing_events (
    event_id    VARCHAR(255) PRIMARY KEY,
    event_type  VARCHAR(80)  NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL
);

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'saas_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE billing_events TO saas_app;
    END IF;
END
$$;
