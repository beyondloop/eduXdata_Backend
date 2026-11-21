ALTER TABLE syllabuses ADD COLUMN year_start DATE;
ALTER TABLE syllabuses ADD CONSTRAINT syllabus_year_starts_on_first CHECK (year_start IS NULL OR extract(day FROM year_start) = 1);
ALTER TABLE syllabus_topics ADD COLUMN deadline_time TIME NOT NULL DEFAULT '23:59:59';
