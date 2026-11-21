ALTER TABLE users RENAME COLUMN user_code TO user_id;
ALTER TABLE users RENAME CONSTRAINT uq_users_tenant_user_code TO uq_users_tenant_user_id;
