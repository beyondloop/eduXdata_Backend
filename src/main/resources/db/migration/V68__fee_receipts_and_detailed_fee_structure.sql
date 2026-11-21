ALTER TABLE student_fee_structures
    ADD COLUMN IF NOT EXISTS development_fee NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS examination_fee NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS game_fee NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS magazine_fee NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS banavihar_fee NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS medicine_fee NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS annual_function_fee NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS puja_festival_fee NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS prantiya_nidhi_fee NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS sanskar_kendra_fee NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS late_fee NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS misc_fee NUMERIC(12,2) NOT NULL DEFAULT 0;

CREATE SEQUENCE IF NOT EXISTS fee_receipt_number_seq START WITH 1;

CREATE TABLE IF NOT EXISTS fee_receipts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    student_id UUID NOT NULL REFERENCES students(id),
    receipt_number VARCHAR(40) NOT NULL,
    academic_year VARCHAR(40) NOT NULL,
    total_amount NUMERIC(12,2) NOT NULL CHECK (total_amount > 0),
    payment_method VARCHAR(30) NOT NULL DEFAULT 'CASH',
    collected_by VARCHAR(180),
    collected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, receipt_number)
);

CREATE TABLE IF NOT EXISTS fee_receipt_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    receipt_id UUID NOT NULL REFERENCES fee_receipts(id) ON DELETE CASCADE,
    fee_type VARCHAR(80) NOT NULL,
    amount NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    applied_to_invoices NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (applied_to_invoices >= 0),
    UNIQUE (receipt_id, fee_type)
);

CREATE INDEX IF NOT EXISTS idx_fee_receipts_tenant_year ON fee_receipts(tenant_id, academic_year, collected_at DESC);
CREATE INDEX IF NOT EXISTS idx_fee_receipts_student ON fee_receipts(tenant_id, student_id, collected_at DESC);
