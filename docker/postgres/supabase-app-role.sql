-- Creates the restricted database role the application runs as on Supabase.
--
-- Why: the default Supabase login (postgres.<project-ref>) has BYPASSRLS, so an app connected as
-- that role ignores every row level security policy. The application must connect as a role
-- without BYPASSRLS. Flyway keeps using the owner login, because it needs to create tables.
--
-- Run once in the Supabase SQL editor, after the first deploy has applied the Flyway migrations.
-- Replace REPLACE_WITH_A_LONG_RANDOM_PASSWORD first. Do not commit a real password.
-- The script can be run again safely.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'saas_app') THEN
        CREATE ROLE saas_app LOGIN PASSWORD 'REPLACE_WITH_A_LONG_RANDOM_PASSWORD'
            NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
END
$$;

ALTER ROLE saas_app NOBYPASSRLS NOSUPERUSER;

GRANT CONNECT ON DATABASE postgres TO saas_app;
GRANT USAGE ON SCHEMA public TO saas_app;

-- Ordinary tables: full row access, narrowed by row level security and the tenant filter.
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
    products,
    users,
    tenants,
    tenant_memberships,
    refresh_tokens,
    password_reset_tokens,
    membership_invites,
    billing_events
TO saas_app;

-- The audit log is append-only: add and read, never change or remove.
GRANT SELECT, INSERT ON TABLE audit_events TO saas_app;
REVOKE UPDATE, DELETE, TRUNCATE ON TABLE audit_events FROM saas_app;

-- Flyway runs as the owner and records its history there; the app does not need it.
REVOKE ALL ON TABLE flyway_schema_history FROM saas_app;

-- Supabase also exposes public tables to its Data API roles by default. This app never uses the
-- Data API, so close it. The Spring backend is the only way in.
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM anon, authenticated;
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM anon, authenticated;

-- Check: expect saas_app | false | false
SELECT rolname, rolsuper, rolbypassrls FROM pg_roles WHERE rolname = 'saas_app';
