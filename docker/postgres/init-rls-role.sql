-- App role used by the Spring Boot datasource.
-- Migrations run as the superuser (saas); the app role is subject to RLS.

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'saas_app') THEN
        CREATE ROLE saas_app LOGIN PASSWORD 'saas_app';
    END IF;
END
$$;

GRANT CONNECT ON DATABASE saas_foundation TO saas_app;
GRANT USAGE ON SCHEMA public TO saas_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO saas_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO saas_app;
