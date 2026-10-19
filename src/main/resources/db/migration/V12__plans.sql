-- Free is the default plan and includes product access. Pro is the Stripe
-- subscription and is what opens the audit log.

ALTER TABLE tenants
    ADD COLUMN plan VARCHAR(16) NOT NULL DEFAULT 'FREE';

ALTER TABLE tenants
    ADD CONSTRAINT ck_tenants_plan CHECK (plan IN ('FREE', 'PRO'));
