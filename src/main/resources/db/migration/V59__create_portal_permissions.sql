CREATE TABLE portal_permissions (
    tenant_id UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    target_role VARCHAR(20) NOT NULL CHECK (target_role IN ('ADMIN', 'TEACHER', 'STUDENT')),
    target_user_id VARCHAR(20) NOT NULL,
    features JSONB NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(features) = 'array'),
    updated_by VARCHAR(20) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, target_role, target_user_id)
);
