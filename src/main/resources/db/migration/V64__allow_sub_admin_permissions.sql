ALTER TABLE portal_permissions
    DROP CONSTRAINT IF EXISTS portal_permissions_target_role_check;

ALTER TABLE portal_permissions
    ADD CONSTRAINT portal_permissions_target_role_check
    CHECK (target_role IN ('ADMIN', 'SUB_ADMIN', 'TEACHER', 'STUDENT'));
