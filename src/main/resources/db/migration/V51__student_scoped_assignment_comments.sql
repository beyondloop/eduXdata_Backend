ALTER TABLE class_assignment_comments
    ADD COLUMN student_id UUID REFERENCES students(id) ON DELETE CASCADE;
CREATE INDEX idx_assignment_comments_student ON class_assignment_comments(tenant_id, assignment_id, student_id, created_at);
