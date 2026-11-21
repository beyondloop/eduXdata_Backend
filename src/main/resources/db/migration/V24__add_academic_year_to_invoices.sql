ALTER TABLE invoices ADD COLUMN IF NOT EXISTS academic_year VARCHAR(9) NOT NULL DEFAULT '2026-27';
CREATE INDEX IF NOT EXISTS idx_invoices_tenant_academic_year ON invoices(tenant_id, academic_year);
