CREATE TABLE school_salary_structure (
    tenant_id UUID PRIMARY KEY REFERENCES tenants(id),
    basic_salary NUMERIC(12,2) NOT NULL CHECK (basic_salary > 0),
    allowances NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (allowances >= 0),
    pf_deduction NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (pf_deduction >= 0),
    other_deductions NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (other_deductions >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (pf_deduction + other_deductions <= basic_salary + allowances)
);

ALTER TABLE teachers ADD COLUMN pf_number VARCHAR(60) NOT NULL DEFAULT '';

CREATE TABLE teacher_salary_slips (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    teacher_id UUID NOT NULL REFERENCES teachers(id),
    salary_month DATE NOT NULL CHECK (extract(day FROM salary_month) = 1),
    school_name VARCHAR(180) NOT NULL,
    employee_name VARCHAR(160) NOT NULL,
    employee_code VARCHAR(60) NOT NULL,
    pf_number VARCHAR(60) NOT NULL DEFAULT '',
    total_days INTEGER NOT NULL CHECK (total_days BETWEEN 28 AND 31),
    lwp_days INTEGER NOT NULL CHECK (lwp_days >= 0 AND lwp_days <= total_days),
    paid_days INTEGER NOT NULL CHECK (paid_days = total_days - lwp_days),
    basic_salary NUMERIC(12,2) NOT NULL,
    allowances NUMERIC(12,2) NOT NULL,
    gross_salary NUMERIC(12,2) NOT NULL,
    lwp_deduction NUMERIC(12,2) NOT NULL,
    pf_deduction NUMERIC(12,2) NOT NULL,
    other_deductions NUMERIC(12,2) NOT NULL,
    total_deductions NUMERIC(12,2) NOT NULL,
    net_salary NUMERIC(12,2) NOT NULL CHECK (net_salary >= 0),
    generated_by VARCHAR(80) NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, teacher_id, salary_month)
);
