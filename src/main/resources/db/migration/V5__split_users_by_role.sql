CREATE TABLE super_admins (id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), user_id VARCHAR(20) NOT NULL, email VARCHAR(180) NOT NULL, password_hash VARCHAR(255) NOT NULL, display_name VARCHAR(160) NOT NULL, active BOOLEAN NOT NULL DEFAULT true, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE (tenant_id, email), UNIQUE (tenant_id, user_id));
CREATE TABLE admins (id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), user_id VARCHAR(20) NOT NULL, email VARCHAR(180) NOT NULL, password_hash VARCHAR(255) NOT NULL, display_name VARCHAR(160) NOT NULL, active BOOLEAN NOT NULL DEFAULT true, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE (tenant_id, email), UNIQUE (tenant_id, user_id));
CREATE TABLE teachers (id UUID PRIMARY KEY, tenant_id UUID NOT NULL REFERENCES tenants(id), user_id VARCHAR(20) NOT NULL, email VARCHAR(180) NOT NULL, password_hash VARCHAR(255) NOT NULL, display_name VARCHAR(160) NOT NULL, active BOOLEAN NOT NULL DEFAULT true, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE (tenant_id, email), UNIQUE (tenant_id, user_id));

ALTER TABLE students ADD COLUMN IF NOT EXISTS user_id VARCHAR(20);
ALTER TABLE students ADD COLUMN IF NOT EXISTS email VARCHAR(180);
ALTER TABLE students ADD COLUMN IF NOT EXISTS password_hash VARCHAR(255);
ALTER TABLE students ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT now();
UPDATE students s SET user_id = u.user_id, email = u.email, password_hash = u.password_hash, display_name = u.display_name, active = u.active FROM users u WHERE u.tenant_id = s.tenant_id AND upper(u.role) = 'STUDENT' AND s.admission_number = u.user_id;
INSERT INTO students (id, tenant_id, admission_number, display_name, email, password_hash, user_id, active)
SELECT u.id, u.tenant_id, u.user_id, u.display_name, u.email, u.password_hash, u.user_id, u.active FROM users u WHERE upper(u.role) = 'STUDENT' AND NOT EXISTS (SELECT 1 FROM students s WHERE s.tenant_id = u.tenant_id AND (s.id = u.id OR s.user_id = u.user_id));
UPDATE students SET user_id = admission_number WHERE user_id IS NULL;
ALTER TABLE students ALTER COLUMN user_id SET NOT NULL;
ALTER TABLE students ADD CONSTRAINT uq_students_tenant_user_id UNIQUE (tenant_id, user_id);
ALTER TABLE students ADD CONSTRAINT uq_students_tenant_email UNIQUE (tenant_id, email);

INSERT INTO super_admins (id, tenant_id, user_id, email, password_hash, display_name, active, created_at) SELECT id, tenant_id, user_id, email, password_hash, display_name, active, created_at FROM users WHERE upper(role) = 'SUPER_ADMIN';
INSERT INTO admins (id, tenant_id, user_id, email, password_hash, display_name, active, created_at) SELECT id, tenant_id, user_id, email, password_hash, display_name, active, created_at FROM users WHERE upper(role) = 'ADMIN';
INSERT INTO teachers (id, tenant_id, user_id, email, password_hash, display_name, active, created_at) SELECT id, tenant_id, user_id, email, password_hash, display_name, active, created_at FROM users WHERE upper(role) = 'TEACHER';

DO $$ DECLARE c RECORD; BEGIN FOR c IN SELECT conrelid::regclass AS table_name, conname FROM pg_constraint WHERE confrelid = 'users'::regclass LOOP EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I', c.table_name, c.conname); END LOOP; END $$;
DROP TABLE users;
