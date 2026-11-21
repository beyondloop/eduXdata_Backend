CREATE TABLE student_fee_structures (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    class_name VARCHAR(40) NOT NULL,
    academic_fee NUMERIC(12,2) NOT NULL DEFAULT 0,
    transport_fee NUMERIC(12,2) NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, class_name)
);

CREATE TABLE teacher_salary_structures (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    teacher_id UUID NOT NULL REFERENCES teachers(id),
    basic_salary NUMERIC(12,2) NOT NULL DEFAULT 0,
    allowances NUMERIC(12,2) NOT NULL DEFAULT 0,
    deductions NUMERIC(12,2) NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, teacher_id)
);

CREATE INDEX idx_teacher_salary_structures_teacher ON teacher_salary_structures (tenant_id, teacher_id);
