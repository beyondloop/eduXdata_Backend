CREATE TABLE admission_enquiries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    student_name VARCHAR(160) NOT NULL,
    parent_name VARCHAR(160) NOT NULL,
    phone VARCHAR(40) NOT NULL,
    email VARCHAR(180),
    desired_class VARCHAR(80) NOT NULL,
    message TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX admission_enquiries_tenant_created_idx ON admission_enquiries(tenant_id, created_at DESC);
