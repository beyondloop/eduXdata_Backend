ALTER TABLE class_assignment_progress
    DROP CONSTRAINT class_assignment_progress_status_check;
ALTER TABLE class_assignment_progress
    ADD CONSTRAINT class_assignment_progress_status_check
    CHECK (status IN ('TODO', 'IN_PROGRESS', 'DONE', 'COMPLETED'));

CREATE TABLE class_assignment_comments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    assignment_id UUID NOT NULL REFERENCES class_assignments(id) ON DELETE CASCADE,
    author_user_id VARCHAR(120) NOT NULL,
    author_name VARCHAR(160) NOT NULL,
    author_role VARCHAR(20) NOT NULL CHECK (author_role IN ('TEACHER', 'STUDENT')),
    body TEXT NOT NULL CHECK (length(body) BETWEEN 1 AND 4000),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_assignment_comments_task ON class_assignment_comments(tenant_id, assignment_id, created_at);
