ALTER TABLE exam_timetables ADD COLUMN exam_term VARCHAR(20) NOT NULL DEFAULT 'Term 1';
ALTER TABLE exam_timetables ALTER COLUMN exam_term DROP DEFAULT;
ALTER TABLE exam_timetables DROP CONSTRAINT exam_timetables_tenant_id_key;
ALTER TABLE exam_timetables ADD CONSTRAINT uq_exam_timetable_tenant_term UNIQUE (tenant_id, exam_term);

CREATE TABLE exam_schedule_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    exam_term VARCHAR(20) NOT NULL,
    subject VARCHAR(160) NOT NULL,
    exam_date DATE NOT NULL,
    exam_time VARCHAR(40) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_exam_schedule_tenant_date ON exam_schedule_items(tenant_id, exam_date);
