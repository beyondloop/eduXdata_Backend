ALTER TABLE syllabus_topics ADD COLUMN workflow_status varchar(20) CHECK (workflow_status IN ('TODO','IN_PROGRESS','DONE'));
