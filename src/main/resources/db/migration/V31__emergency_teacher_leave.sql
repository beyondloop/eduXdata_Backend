ALTER TABLE teacher_leave DROP CONSTRAINT teacher_leave_leave_type_check;
ALTER TABLE teacher_leave ADD CONSTRAINT teacher_leave_leave_type_check CHECK (leave_type IN ('SICK', 'CASUAL', 'EMERGENCY'));
