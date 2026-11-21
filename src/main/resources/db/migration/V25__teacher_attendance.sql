CREATE TABLE teacher_attendance (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    teacher_id UUID NOT NULL REFERENCES teachers(id),
    attendance_date DATE NOT NULL,
    hours NUMERIC(4,2) NOT NULL CHECK (hours > 0 AND hours <= 24),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PRESENT', 'PENDING', 'REJECTED')),
    approver_id UUID REFERENCES admins(id),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at TIMESTAMPTZ,
    UNIQUE (tenant_id, teacher_id, attendance_date)
);
CREATE INDEX teacher_attendance_approvals ON teacher_attendance(tenant_id, approver_id, status);
