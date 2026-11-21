ALTER TABLE student_fee_structures ADD COLUMN IF NOT EXISTS transport_0_5_fee NUMERIC(12,2) NOT NULL DEFAULT 2000;
ALTER TABLE student_fee_structures ADD COLUMN IF NOT EXISTS transport_5_10_fee NUMERIC(12,2) NOT NULL DEFAULT 5000;

UPDATE student_fee_structures
SET transport_0_5_fee = 2000,
    transport_5_10_fee = 5000
WHERE transport_0_5_fee = 0 OR transport_5_10_fee = 0;

ALTER TABLE students ADD COLUMN IF NOT EXISTS transport_enabled BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE students ADD COLUMN IF NOT EXISTS transport_distance_band VARCHAR(10);
ALTER TABLE students DROP CONSTRAINT IF EXISTS chk_students_transport_distance_band;
ALTER TABLE students ADD CONSTRAINT chk_students_transport_distance_band
    CHECK (transport_distance_band IS NULL OR transport_distance_band IN ('0-5', '5-10'));
