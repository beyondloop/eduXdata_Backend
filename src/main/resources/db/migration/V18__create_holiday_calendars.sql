CREATE TABLE holiday_calendars (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    event_name VARCHAR(200) NOT NULL,
    holiday_date DATE NOT NULL,
    image_data TEXT,
    -- Role-specific account tables replaced the legacy `users` table in V5.
    -- Keep the creator identifier for auditing without a reference to that removed table.
    created_by UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, holiday_date, event_name)
);

CREATE INDEX idx_holiday_calendars_tenant_date ON holiday_calendars(tenant_id, holiday_date);
