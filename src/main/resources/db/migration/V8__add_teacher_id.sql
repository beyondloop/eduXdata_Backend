ALTER TABLE teachers ADD COLUMN IF NOT EXISTS teacher_id VARCHAR(60);

UPDATE teachers SET teacher_id = user_id WHERE teacher_id IS NULL;

ALTER TABLE teachers ALTER COLUMN teacher_id SET NOT NULL;
ALTER TABLE teachers ADD CONSTRAINT uq_teachers_tenant_teacher_id UNIQUE (tenant_id, teacher_id);
