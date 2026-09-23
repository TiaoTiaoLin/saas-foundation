-- PostgreSQL Row-Level Security: isolate rows by app.current_tenant GUC

ALTER TABLE products ENABLE ROW LEVEL SECURITY;
ALTER TABLE products FORCE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS products_tenant_isolation ON products;

CREATE POLICY products_tenant_isolation ON products
    FOR ALL
    USING (
        tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
    )
    WITH CHECK (
        tenant_id = NULLIF(current_setting('app.current_tenant', true), '')::uuid
    );

COMMENT ON POLICY products_tenant_isolation ON products IS
    'Restricts all DML to rows matching SET LOCAL app.current_tenant';
