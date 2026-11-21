ALTER TABLE teacher_leave
    ADD COLUMN proof_name VARCHAR(180),
    ADD COLUMN proof_type VARCHAR(100),
    ADD COLUMN proof_data BYTEA;
