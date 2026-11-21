CREATE TABLE student_leave (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id UUID NOT NULL REFERENCES tenants(id),
 student_id UUID NOT NULL REFERENCES students(id),
 teacher_user_id VARCHAR(60) NOT NULL,
 leave_type VARCHAR(20) NOT NULL CHECK (leave_type IN ('SICK','CASUAL','EMERGENCY','OTHER')),
 from_date DATE NOT NULL,
 to_date DATE NOT NULL CHECK (to_date >= from_date),
 comments VARCHAR(1000) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
 submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 reviewed_at TIMESTAMPTZ,
 reviewed_by VARCHAR(60)
);
CREATE INDEX idx_student_leave_student ON student_leave(tenant_id, student_id);
CREATE INDEX idx_student_leave_teacher ON student_leave(tenant_id, teacher_user_id, status);
