CREATE TABLE syllabuses (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants(id),
    title VARCHAR(180) NOT NULL,
    class_name VARCHAR(20) NOT NULL,
    section_name VARCHAR(10) NOT NULL,
    subject VARCHAR(100) NOT NULL,
    file_name VARCHAR(180),
    file_data BYTEA,
    created_by VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_syllabuses_tenant_class ON syllabuses(tenant_id, class_name, section_name, subject);

CREATE TABLE syllabus_topics (
    id UUID PRIMARY KEY,
    syllabus_id UUID NOT NULL REFERENCES syllabuses(id) ON DELETE CASCADE,
    title VARCHAR(200) NOT NULL,
    deadline DATE NOT NULL,
    position INTEGER NOT NULL,
    completed_at TIMESTAMPTZ,
    completed_by VARCHAR(20),
    CHECK ((completed_at IS NULL) = (completed_by IS NULL))
);
CREATE INDEX idx_syllabus_topics_plan ON syllabus_topics(syllabus_id, position);

CREATE TABLE syllabus_notes (
    id UUID PRIMARY KEY,
    topic_id UUID NOT NULL REFERENCES syllabus_topics(id) ON DELETE CASCADE,
    author_id VARCHAR(20) NOT NULL,
    body VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_syllabus_notes_topic ON syllabus_notes(topic_id, created_at);
