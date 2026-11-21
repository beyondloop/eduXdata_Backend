CREATE TABLE class_syllabus_documents (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
 class_name VARCHAR(20) NOT NULL, academic_year VARCHAR(9) NOT NULL,
 period VARCHAR(12) NOT NULL CHECK (period IN ('FULL_YEAR','TERM_1','TERM_2','FINAL')),
 file_name VARCHAR(180) NOT NULL, file_data BYTEA NOT NULL,
 created_by VARCHAR(20) NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE (tenant_id,class_name,academic_year,period)
);
