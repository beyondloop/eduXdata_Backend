CREATE TABLE exam_timetables (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL UNIQUE REFERENCES tenants(id),
    file_name VARCHAR(180) NOT NULL,
    file_data BYTEA NOT NULL,
    uploaded_by VARCHAR(80) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
