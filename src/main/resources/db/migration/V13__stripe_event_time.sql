-- Stripe event.created for the newest billing event applied to the organization.
-- An older delivery must not replace a newer plan.

ALTER TABLE tenants
    ADD COLUMN stripe_event_created_at TIMESTAMPTZ;

-- The row already reflects the newest event that committed. Ignore Stripe
-- snapshots created before this migration; a later cancellation still applies.
UPDATE tenants
SET stripe_event_created_at = NOW()
WHERE subscription_status <> 'NONE';
