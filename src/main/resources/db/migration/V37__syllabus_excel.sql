ALTER TABLE syllabuses ADD COLUMN academic_year VARCHAR(9);
ALTER TABLE syllabuses ADD COLUMN subject_code VARCHAR(40);
ALTER TABLE syllabuses ADD COLUMN assigned_teacher VARCHAR(20);
ALTER TABLE syllabus_topics ADD COLUMN chapter VARCHAR(200);
ALTER TABLE syllabus_topics ADD COLUMN description VARCHAR(2000);
ALTER TABLE syllabus_topics ADD COLUMN start_date DATE;
ALTER TABLE syllabus_topics ADD COLUMN priority VARCHAR(6) CHECK (priority IN ('LOW','MEDIUM','HIGH'));
ALTER TABLE syllabus_topics ADD COLUMN import_key VARCHAR(64);
ALTER TABLE syllabus_topics ADD COLUMN points INTEGER NOT NULL DEFAULT 0;
ALTER TABLE syllabus_topics ADD COLUMN days_late INTEGER NOT NULL DEFAULT 0;
ALTER TABLE syllabus_topics ADD COLUMN completion_status VARCHAR(20);
CREATE TABLE syllabus_imports (
 id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id),
 created_by VARCHAR(20) NOT NULL, workbook BYTEA NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL DEFAULT now() + interval '30 minutes',
 imported_at TIMESTAMPTZ
);
CREATE TABLE syllabus_import_keys (
 tenant_id UUID NOT NULL REFERENCES tenants(id), import_key VARCHAR(64) NOT NULL,
 PRIMARY KEY (tenant_id, import_key)
);
UPDATE syllabus_topics SET
 days_late = greatest(0, (completed_at AT TIME ZONE 'Asia/Kolkata')::date - deadline),
 points = CASE WHEN (completed_at AT TIME ZONE 'Asia/Kolkata') > deadline + deadline_time THEN 5 ELSE 10 END,
 completion_status = CASE WHEN (completed_at AT TIME ZONE 'Asia/Kolkata') > deadline + deadline_time THEN 'LATE' ELSE 'ON_TIME' END
 WHERE completed_at IS NOT NULL;
