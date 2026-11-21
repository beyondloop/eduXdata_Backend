CREATE TABLE student_monthly_fees (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    student_id UUID NOT NULL REFERENCES students(id),
    academic_year VARCHAR(40) NOT NULL,
    month_start DATE NOT NULL,
    month_label VARCHAR(40) NOT NULL,
    fee_type VARCHAR(80) NOT NULL,
    amount NUMERIC(12,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    payment_method VARCHAR(30),
    provider_reference VARCHAR(120),
    paid_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, student_id, month_start, fee_type)
);

CREATE INDEX idx_student_monthly_fees_student
    ON student_monthly_fees (tenant_id, student_id, month_start);
