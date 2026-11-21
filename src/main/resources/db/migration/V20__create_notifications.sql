CREATE TABLE notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    title VARCHAR(200) NOT NULL,
    body TEXT NOT NULL,
    audience VARCHAR(40) NOT NULL DEFAULT 'ALL',
    published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by VARCHAR(60)
);
CREATE INDEX idx_notifications_tenant_published ON notifications(tenant_id, published_at DESC);
