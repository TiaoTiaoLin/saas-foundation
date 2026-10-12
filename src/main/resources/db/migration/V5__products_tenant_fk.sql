-- products.tenant_id now references a registered tenant. Rows created while any
-- UUID was accepted cannot satisfy the foreign key, so they are removed first.
-- Flyway runs as the migration role, which bypasses row-level security.

DELETE FROM products
WHERE NOT EXISTS (
    SELECT 1 FROM tenants WHERE tenants.id = products.tenant_id
);

ALTER TABLE products
    ADD CONSTRAINT fk_products_tenant
    FOREIGN KEY (tenant_id) REFERENCES tenants (id) ON DELETE CASCADE;
