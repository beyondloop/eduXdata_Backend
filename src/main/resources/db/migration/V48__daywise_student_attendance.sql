-- Keep one attendance record per student/date, including a snapshot of the class teacher.
ALTER TABLE attendance
    ADD COLUMN attendance_day VARCHAR(9) GENERATED ALWAYS AS (
        CASE EXTRACT(ISODOW FROM attendance_date)::INTEGER
            WHEN 1 THEN 'MONDAY' WHEN 2 THEN 'TUESDAY' WHEN 3 THEN 'WEDNESDAY'
            WHEN 4 THEN 'THURSDAY' WHEN 5 THEN 'FRIDAY' WHEN 6 THEN 'SATURDAY'
            WHEN 7 THEN 'SUNDAY' END
    ) STORED,
    ADD COLUMN class_name VARCHAR(80),
    ADD COLUMN section_name VARCHAR(30),
    ADD COLUMN class_teacher_user_id VARCHAR(20),
    ADD COLUMN class_teacher_name VARCHAR(160);

-- Also covers attendance written by approved student leave requests.
CREATE FUNCTION snapshot_attendance_class_teacher() RETURNS trigger AS $$
BEGIN
    SELECT s.class_name, s.section_name INTO NEW.class_name, NEW.section_name
    FROM students s WHERE s.id = NEW.student_id AND s.tenant_id = NEW.tenant_id;

    SELECT w.teacher_user_id, COALESCE(t.display_name, w.teacher_name)
    INTO NEW.class_teacher_user_id, NEW.class_teacher_name
    FROM teacher_workloads w
    LEFT JOIN teachers t ON t.tenant_id = w.tenant_id AND t.user_id = w.teacher_user_id
    WHERE w.tenant_id = NEW.tenant_id AND w.status = 'ACTIVE'
      AND lower(trim(w.teacher_role)) = 'class teacher'
      AND lower(w.class_name) = lower(regexp_replace(coalesce(NEW.class_name, ''), '^class\s*', '', 'i'))
      AND lower(w.section_name) = lower(coalesce(NEW.section_name, ''))
    ORDER BY (t.id = NEW.marked_by) DESC NULLS LAST,
             (w.day_of_week = upper(trim(to_char(NEW.attendance_date, 'Day')))) DESC,
             w.created_at DESC, w.id
    LIMIT 1;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER attendance_class_teacher_snapshot
BEFORE INSERT OR UPDATE ON attendance
FOR EACH ROW EXECUTE FUNCTION snapshot_attendance_class_teacher();

-- Populate available class/teacher information for records already stored.
UPDATE attendance SET status = status;
