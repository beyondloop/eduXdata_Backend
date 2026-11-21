CREATE TABLE admin_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    student_id UUID NOT NULL REFERENCES students(id),
    file_name VARCHAR(180) NOT NULL,
    file_data BYTEA NOT NULL,
    uploaded_by VARCHAR(20) NOT NULL,
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_admin_documents_tenant_student ON admin_documents(tenant_id, student_id);
ALTER TABLE admins ADD COLUMN profile_image TEXT;
