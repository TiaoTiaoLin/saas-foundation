-- The audit log is append-only for the application role.
--
-- docker/postgres/init-rls-role.sql gives saas_app default privileges on every table created
-- later, and that included UPDATE and DELETE on audit_events. V11 only added privileges, so it
-- never took them away. Revoke them here. The role may not exist (for example, before it is
-- created on a hosted database), in which case there is nothing to revoke.

DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'saas_app') THEN
        REVOKE UPDATE, DELETE, TRUNCATE ON TABLE audit_events FROM saas_app;
    END IF;
END
$$;
