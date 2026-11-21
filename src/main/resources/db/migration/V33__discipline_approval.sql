-- Preserve incidents recorded before the approval workflow as approved points.
ALTER TABLE student_discipline ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'APPROVED'
 CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'));
ALTER TABLE student_discipline ALTER COLUMN status SET DEFAULT 'PENDING';
ALTER TABLE student_discipline ADD COLUMN reviewed_by VARCHAR(20);
ALTER TABLE student_discipline ADD COLUMN reviewed_at TIMESTAMPTZ;
CREATE INDEX idx_discipline_pending ON student_discipline(tenant_id, status);
