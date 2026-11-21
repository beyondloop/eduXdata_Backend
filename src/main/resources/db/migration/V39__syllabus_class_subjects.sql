CREATE TABLE syllabus_class_subjects (
 tenant_id UUID NOT NULL REFERENCES tenants(id),
 class_name VARCHAR(80) NOT NULL,
 subject VARCHAR(100) NOT NULL,
 PRIMARY KEY (tenant_id, class_name, subject)
);
