ALTER TABLE students ADD COLUMN IF NOT EXISTS roll_number VARCHAR(60);

UPDATE students SET roll_number = admission_number WHERE roll_number IS NULL;
