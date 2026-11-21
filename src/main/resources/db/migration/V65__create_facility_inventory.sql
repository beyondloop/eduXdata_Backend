CREATE TABLE facility_products (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name VARCHAR(160) NOT NULL,
    category VARCHAR(20) NOT NULL CHECK (category IN ('BAG', 'UNIFORM', 'BOOK', 'OTHER')),
    size VARCHAR(40),
    available_quantity INTEGER NOT NULL DEFAULT 0 CHECK (available_quantity >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE facility_student_payments (
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES facility_products(id) ON DELETE CASCADE,
    student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    paid BOOLEAN NOT NULL DEFAULT false,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, product_id, student_id)
);

CREATE TABLE facility_allocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES facility_products(id) ON DELETE CASCADE,
    student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    quantity INTEGER NOT NULL CHECK (quantity > 0),
    allocated_by VARCHAR(20) NOT NULL,
    allocated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX ix_facility_products_tenant ON facility_products(tenant_id, category, name);
CREATE INDEX ix_facility_allocations_product_student ON facility_allocations(tenant_id, product_id, student_id);
