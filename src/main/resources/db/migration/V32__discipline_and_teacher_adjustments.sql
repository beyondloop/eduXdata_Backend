CREATE TABLE student_discipline (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
 student_id UUID NOT NULL REFERENCES students(id), incident VARCHAR(160) NOT NULL,
 points INTEGER NOT NULL CHECK (points > 0 AND points <= 100),
 incident_date DATE NOT NULL, notes VARCHAR(2000) NOT NULL DEFAULT '',
 recorded_by VARCHAR(20) NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_discipline_student ON student_discipline(tenant_id, student_id);
CREATE TABLE teacher_adjustments (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
 workload_id UUID NOT NULL REFERENCES teacher_workloads(id) ON DELETE CASCADE,
 requested_by VARCHAR(20) NOT NULL, replacement_user_id VARCHAR(20) NOT NULL,
 reason VARCHAR(2000) NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING'
 CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
 reviewed_by VARCHAR(20), reviewed_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_adjustment_pending ON teacher_adjustments(workload_id) WHERE status = 'PENDING';
