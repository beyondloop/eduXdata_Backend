ALTER TABLE students ADD COLUMN IF NOT EXISTS registration_type VARCHAR(3) NOT NULL DEFAULT 'OLD';
ALTER TABLE teachers ADD COLUMN IF NOT EXISTS registration_type VARCHAR(3) NOT NULL DEFAULT 'OLD';

ALTER TABLE students DROP CONSTRAINT IF EXISTS chk_students_registration_type;
ALTER TABLE students ADD CONSTRAINT chk_students_registration_type CHECK (registration_type IN ('OLD', 'NEW'));
ALTER TABLE teachers DROP CONSTRAINT IF EXISTS chk_teachers_registration_type;
ALTER TABLE teachers ADD CONSTRAINT chk_teachers_registration_type CHECK (registration_type IN ('OLD', 'NEW'));
