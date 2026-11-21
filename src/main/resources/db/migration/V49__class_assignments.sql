CREATE TABLE class_assignments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    teacher_user_id VARCHAR(120) NOT NULL,
    teacher_name VARCHAR(160) NOT NULL,
    class_name VARCHAR(20) NOT NULL,
    section_name VARCHAR(30) NOT NULL,
    subject VARCHAR(120) NOT NULL,
    title VARCHAR(200) NOT NULL,
    instructions TEXT NOT NULL DEFAULT '',
    file_name VARCHAR(180),
    content_type VARCHAR(120),
    file_data BYTEA,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_class_assignments_teacher ON class_assignments(tenant_id, teacher_user_id, created_at DESC);
CREATE TABLE class_assignment_progress (
    assignment_id UUID NOT NULL REFERENCES class_assignments(id) ON DELETE CASCADE,
    student_id UUID NOT NULL REFERENCES students(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL DEFAULT 'TODO' CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE')),
    feedback TEXT,
    reviewed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (assignment_id, student_id)
);
