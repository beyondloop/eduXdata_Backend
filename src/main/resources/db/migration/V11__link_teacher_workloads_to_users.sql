ALTER TABLE teacher_workloads ADD COLUMN IF NOT EXISTS teacher_user_id VARCHAR(20);

UPDATE teacher_workloads workload
SET teacher_user_id = teacher.user_id
FROM teachers teacher
WHERE workload.tenant_id = teacher.tenant_id
  AND workload.teacher_user_id IS NULL
  AND lower(workload.teacher_name) = lower(teacher.display_name);

CREATE INDEX IF NOT EXISTS idx_teacher_workloads_teacher_user
    ON teacher_workloads (tenant_id, teacher_user_id);
