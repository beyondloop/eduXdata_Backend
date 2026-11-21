ALTER TABLE invoices ADD COLUMN IF NOT EXISTS month_start DATE;
ALTER TABLE invoices ADD COLUMN IF NOT EXISTS fee_type VARCHAR(80);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS payment_method VARCHAR(30);

CREATE UNIQUE INDEX IF NOT EXISTS uq_monthly_student_invoice
    ON invoices (tenant_id, student_id, month_start, fee_type)
    WHERE month_start IS NOT NULL AND fee_type IS NOT NULL;
