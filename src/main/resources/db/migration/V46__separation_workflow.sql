ALTER TABLE admin_documents ALTER COLUMN student_id DROP NOT NULL;

CREATE TABLE separation_checklist_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    person_type VARCHAR(10) NOT NULL CHECK (person_type IN ('STUDENT', 'TEACHER')),
    label VARCHAR(500) NOT NULL,
    sort_order INTEGER NOT NULL,
    UNIQUE (tenant_id, person_type, sort_order)
);
CREATE TABLE separation_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    student_id UUID REFERENCES students(id),
    teacher_id UUID REFERENCES teachers(id),
    requested_last_day DATE NOT NULL,
    reason VARCHAR(2000) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    created_by VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    CHECK ((student_id IS NOT NULL)::integer + (teacher_id IS NOT NULL)::integer = 1)
);
CREATE UNIQUE INDEX separation_open_student ON separation_requests(tenant_id, student_id) WHERE status = 'IN_PROGRESS';
CREATE UNIQUE INDEX separation_open_teacher ON separation_requests(tenant_id, teacher_id) WHERE status = 'IN_PROGRESS';
CREATE TABLE separation_checklist_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    request_id UUID NOT NULL REFERENCES separation_requests(id) ON DELETE CASCADE,
    label VARCHAR(500) NOT NULL,
    sort_order INTEGER NOT NULL,
    completed BOOLEAN NOT NULL DEFAULT false,
    updated_by VARCHAR(20),
    updated_at TIMESTAMPTZ
);
CREATE INDEX separation_requests_tenant ON separation_requests(tenant_id, created_at);
CREATE INDEX separation_items_request ON separation_checklist_items(request_id);

-- Preserve the existing clearance instructions as editable database templates.
INSERT INTO separation_checklist_templates(tenant_id, person_type, label, sort_order)
SELECT tenant.id, rule.person_type, rule.label, rule.sort_order
FROM tenants tenant CROSS JOIN (VALUES
    ('STUDENT', 'Confirm student identity, admission number and requested last day.', 1),
    ('STUDENT', 'Clear fees, library books, transport dues and school property.', 2),
    ('STUDENT', 'Verify the transfer certificate or supporting request.', 3),
    ('STUDENT', 'Notify the parent or guardian about completed clearance.', 4),
    ('TEACHER', 'Confirm the separation request and final working day.', 1),
    ('TEACHER', 'Complete handover of classes, marks, lesson plans and assets.', 2),
    ('TEACHER', 'Clear payroll, leave, advances and reimbursements.', 3),
    ('TEACHER', 'Record the authorised clearance decision.', 4)
) AS rule(person_type, label, sort_order);
