CREATE TABLE teacher_workloads (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    teacher_name VARCHAR(160) NOT NULL,
    teacher_role VARCHAR(80) NOT NULL,
    subject VARCHAR(100) NOT NULL,
    class_name VARCHAR(20) NOT NULL,
    section_name VARCHAR(10) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    duration_minutes INTEGER NOT NULL DEFAULT 60,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_teacher_workload_duration CHECK (duration_minutes > 0),
    CONSTRAINT chk_teacher_workload_time CHECK (end_time > start_time)
);

CREATE INDEX idx_teacher_workloads_tenant ON teacher_workloads(tenant_id);
