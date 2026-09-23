-- Products table (shared schema, tenant discriminator column)
CREATE TABLE products (
    id          UUID         PRIMARY KEY,
    tenant_id   UUID         NOT NULL,
    name        VARCHAR(128) NOT NULL,
    sku         VARCHAR(64)  NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    version     BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_products_tenant_sku UNIQUE (tenant_id, sku)
);

CREATE INDEX idx_products_tenant_id ON products (tenant_id);
CREATE INDEX idx_products_tenant_active ON products (tenant_id, active);

-- Application role privileges (role created in docker init script; noop-safe for CI)
DO $$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname = 'saas_app') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE products TO saas_app;
    END IF;
END
$$;
