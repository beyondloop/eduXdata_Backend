CREATE TABLE teacher_leave (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    teacher_id UUID NOT NULL REFERENCES teachers(id),
    leave_type VARCHAR(10) NOT NULL CHECK (leave_type IN ('SICK', 'CASUAL')),
    from_date DATE NOT NULL,
    to_date DATE NOT NULL CHECK (to_date >= from_date),
    reason VARCHAR(1000) NOT NULL DEFAULT '',
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    reviewed_by UUID REFERENCES admins(id),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_at TIMESTAMPTZ
);
CREATE INDEX teacher_leave_calendar ON teacher_leave(tenant_id, teacher_id, from_date, to_date);
