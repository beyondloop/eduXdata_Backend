-- The scheduled retention job removes attendance as soon as it reaches one year old.
CREATE INDEX IF NOT EXISTS idx_attendance_retention ON attendance(attendance_date);
