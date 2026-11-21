CREATE TABLE teacher_payroll_structures (
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    teacher_id UUID NOT NULL REFERENCES teachers(id),
    basic_salary NUMERIC(12,2) NOT NULL CHECK (basic_salary > 0),
    allowances NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (allowances >= 0),
    pf_deduction NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (pf_deduction >= 0),
    other_deductions NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (other_deductions >= 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, teacher_id),
    CHECK (pf_deduction + other_deductions <= basic_salary + allowances)
);

-- Preserve individually configured salaries first, then copy the previous common
-- structure to existing teachers. Newly added teachers require explicit setup.
INSERT INTO teacher_payroll_structures(tenant_id, teacher_id, basic_salary, allowances, other_deductions)
SELECT s.tenant_id, s.teacher_id, s.basic_salary, s.allowances, s.deductions
FROM teacher_salary_structures s JOIN teachers t ON t.id = s.teacher_id AND t.tenant_id = s.tenant_id
WHERE s.basic_salary > 0 AND s.allowances >= 0 AND s.deductions >= 0 AND s.deductions <= s.basic_salary + s.allowances;

INSERT INTO teacher_payroll_structures(tenant_id, teacher_id, basic_salary, allowances, pf_deduction, other_deductions)
SELECT t.tenant_id, t.id, s.basic_salary, s.allowances, s.pf_deduction, s.other_deductions
FROM teachers t JOIN school_salary_structure s ON s.tenant_id = t.tenant_id
ON CONFLICT (tenant_id, teacher_id) DO NOTHING;
