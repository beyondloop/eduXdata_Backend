ALTER TABLE class_assignment_progress
    ADD COLUMN student_name VARCHAR(160),
    ADD COLUMN roll_number VARCHAR(80),
    ADD COLUMN assigned_at TIMESTAMPTZ,
    ADD COLUMN completed_at TIMESTAMPTZ;

UPDATE class_assignment_progress p
SET student_name = s.display_name,
    roll_number = s.roll_number,
    assigned_at = a.created_at,
    completed_at = CASE WHEN p.status = 'COMPLETED' THEN p.reviewed_at END
FROM students s, class_assignments a
WHERE p.student_id = s.id
  AND p.assignment_id = a.id;

UPDATE class_assignment_progress p
SET assigned_at = a.created_at
FROM class_assignments a
WHERE p.assignment_id = a.id
  AND p.assigned_at IS NULL;

ALTER TABLE class_assignment_progress
    ALTER COLUMN student_name SET NOT NULL,
    ALTER COLUMN assigned_at SET NOT NULL;

CREATE INDEX idx_assignment_progress_student_status
    ON class_assignment_progress(student_id, status, assigned_at DESC);
