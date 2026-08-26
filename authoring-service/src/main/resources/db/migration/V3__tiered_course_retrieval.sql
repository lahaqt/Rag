CREATE TABLE course_retrieval_relations (
    anchor_course_id VARCHAR(128) NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    related_course_id VARCHAR(128) NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    relation_type VARCHAR(32) NOT NULL
        CHECK (relation_type IN ('PREREQUISITE','COREQUISITE','EQUIVALENT','PROGRAM','DEPARTMENT','INSTITUTION')),
    scope_weight DOUBLE PRECISION NOT NULL
        CHECK (scope_weight > 0.0 AND scope_weight <= 1.0),
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(anchor_course_id, related_course_id),
    CHECK (anchor_course_id <> related_course_id)
);

CREATE INDEX course_retrieval_relations_anchor_idx
    ON course_retrieval_relations(anchor_course_id, enabled, scope_weight DESC);
