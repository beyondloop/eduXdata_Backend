ALTER TABLE students ADD COLUMN IF NOT EXISTS class_name VARCHAR(80);
ALTER TABLE students ADD COLUMN IF NOT EXISTS section_name VARCHAR(30);

UPDATE students s
SET class_name = c.name,
    section_name = sec.name
FROM sections sec
JOIN classes c ON c.id = sec.class_id
WHERE s.section_id = sec.id
  AND (s.class_name IS NULL OR s.section_name IS NULL);
