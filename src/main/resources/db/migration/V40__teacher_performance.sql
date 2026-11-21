ALTER TABLE syllabuses DROP CONSTRAINT syllabus_period_valid;
ALTER TABLE syllabuses ADD CONSTRAINT syllabus_period_valid CHECK (period IN ('FULL_YEAR','TERM_1','TERM_2','FINAL'));
ALTER TABLE syllabus_imports ADD COLUMN period VARCHAR(12) NOT NULL DEFAULT 'FULL_YEAR' CHECK (period IN ('FULL_YEAR','TERM_1','TERM_2','FINAL'));
CREATE TABLE syllabus_point_rules (
 tenant_id UUID PRIMARY KEY REFERENCES tenants(id),
 on_time INTEGER NOT NULL DEFAULT 5 CHECK (on_time BETWEEN 0 AND 1000),
 late INTEGER NOT NULL DEFAULT 0 CHECK (late BETWEEN 0 AND 1000),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE teacher_performance_remarks (
 tenant_id UUID NOT NULL REFERENCES tenants(id), teacher_user_id VARCHAR(20) NOT NULL,
 academic_year VARCHAR(20) NOT NULL, remarks VARCHAR(4000) NOT NULL,
 updated_by VARCHAR(20) NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY (tenant_id, teacher_user_id, academic_year)
);
