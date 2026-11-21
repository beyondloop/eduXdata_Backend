ALTER TABLE facility_products
    ADD COLUMN active BOOLEAN NOT NULL DEFAULT true;

CREATE INDEX ix_facility_products_active_tenant
    ON facility_products(tenant_id, active, category, name);
