ALTER TABLE users ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
CREATE INDEX idx_users_tenant_created_at ON users(tenant_id, created_at);
