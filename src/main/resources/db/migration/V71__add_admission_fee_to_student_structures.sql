ALTER TABLE student_fee_structures
    ADD COLUMN IF NOT EXISTS admission_fee NUMERIC(12,2) NOT NULL DEFAULT 0;
