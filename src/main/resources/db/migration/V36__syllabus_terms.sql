ALTER TABLE syllabuses ADD COLUMN period VARCHAR(12) NOT NULL DEFAULT 'FULL_YEAR';
ALTER TABLE syllabuses ADD CONSTRAINT syllabus_period_valid CHECK (period IN ('FULL_YEAR', 'TERM_1', 'TERM_2'));
