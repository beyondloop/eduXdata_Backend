ALTER TABLE student_fee_structures ADD COLUMN IF NOT EXISTS transport_10_15_fee NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE student_fee_structures ADD COLUMN IF NOT EXISTS transport_15_20_fee NUMERIC(12,2) NOT NULL DEFAULT 0;
ALTER TABLE student_fee_structures ADD COLUMN IF NOT EXISTS transport_20_above_fee NUMERIC(12,2) NOT NULL DEFAULT 0;

ALTER TABLE students DROP CONSTRAINT IF EXISTS chk_students_transport_distance_band;
ALTER TABLE students ADD CONSTRAINT chk_students_transport_distance_band
    CHECK (transport_distance_band IS NULL OR transport_distance_band IN ('0-5', '5-10', '10-15', '15-20', '20-above'));
