CREATE TABLE courses (
    id VARCHAR(128) PRIMARY KEY,
    code VARCHAR(64) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    content_status VARCHAR(32) NOT NULL DEFAULT 'PROVISIONING'
        CHECK (content_status IN ('PROVISIONING','READY','FAILED')),
    published BOOLEAN NOT NULL DEFAULT false,
    archived BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE course_outcomes (
    id VARCHAR(128) PRIMARY KEY,
    course_id VARCHAR(128) NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    code VARCHAR(64) NOT NULL,
    description TEXT NOT NULL,
    display_order INT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT true,
    UNIQUE(course_id, code)
);

CREATE TABLE course_materials (
    id VARCHAR(128) PRIMARY KEY,
    course_id VARCHAR(128) NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    document_id VARCHAR(128) NOT NULL,
    file_name VARCHAR(512) NOT NULL,
    status VARCHAR(32) NOT NULL,
    chunk_count INT NOT NULL DEFAULT 0,
    error_message TEXT NOT NULL DEFAULT '',
    uploaded_at TIMESTAMPTZ,
    UNIQUE(course_id, document_id)
);

CREATE TABLE runtime_mcp_servers (
    id VARCHAR(128) PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    transport VARCHAR(64) NOT NULL,
    endpoint TEXT NOT NULL DEFAULT '',
    command TEXT NOT NULL DEFAULT '',
    args_json TEXT NOT NULL DEFAULT '[]',
    environment_ciphertext TEXT NOT NULL DEFAULT '',
    working_directory TEXT NOT NULL DEFAULT '',
    bearer_token_ciphertext TEXT NOT NULL DEFAULT '',
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE course_mcp_bindings (
    course_id VARCHAR(128) NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    server_id VARCHAR(128) NOT NULL,
    allowed_tools_json TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    PRIMARY KEY(course_id, server_id)
);

CREATE TABLE projects (
    id VARCHAR(128) PRIMARY KEY,
    user_id VARCHAR(200) NOT NULL,
    course_id VARCHAR(128) NOT NULL REFERENCES courses(id),
    title VARCHAR(200) NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX projects_owner_idx ON projects(user_id, updated_at DESC);

CREATE TABLE project_outcomes (
    project_id VARCHAR(128) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    outcome_id VARCHAR(128) NOT NULL REFERENCES course_outcomes(id),
    PRIMARY KEY(project_id, outcome_id)
);

CREATE TABLE artifacts (
    id VARCHAR(128) PRIMARY KEY,
    project_id VARCHAR(128) NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    type VARCHAR(64) NOT NULL,
    title VARCHAR(200) NOT NULL,
    draft_json TEXT NOT NULL,
    draft_version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE revisions (
    id VARCHAR(128) PRIMARY KEY,
    artifact_id VARCHAR(128) NOT NULL REFERENCES artifacts(id) ON DELETE CASCADE,
    revision_number INT NOT NULL,
    title VARCHAR(200) NOT NULL,
    draft_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(artifact_id, revision_number)
);

CREATE TABLE reviews (
    id VARCHAR(128) PRIMARY KEY,
    revision_id VARCHAR(128) NOT NULL REFERENCES revisions(id) ON DELETE CASCADE,
    status VARCHAR(64) NOT NULL,
    overall_score DOUBLE PRECISION,
    dimensions_json TEXT NOT NULL,
    evidence_json TEXT NOT NULL,
    tool_observations_json TEXT NOT NULL DEFAULT '[]',
    summary TEXT NOT NULL DEFAULT '',
    trace_id VARCHAR(128) NOT NULL DEFAULT '',
    trace_json TEXT NOT NULL DEFAULT '[]',
    failure_reason TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE review_runs (
    id VARCHAR(128) PRIMARY KEY,
    revision_id VARCHAR(128) NOT NULL REFERENCES revisions(id) ON DELETE CASCADE,
    user_id VARCHAR(200) NOT NULL,
    idempotency_key VARCHAR(200),
    status VARCHAR(32) NOT NULL CHECK (status IN ('QUEUED','RUNNING','COMPLETED','FAILED','CANCELLED')),
    trace_id VARCHAR(128) NOT NULL DEFAULT '',
    current_phase VARCHAR(128) NOT NULL DEFAULT 'queued',
    state_json TEXT NOT NULL DEFAULT '{}',
    trace_json TEXT NOT NULL DEFAULT '[]',
    model_snapshot_json TEXT NOT NULL,
    failure_reason TEXT NOT NULL DEFAULT '',
    recoverable BOOLEAN NOT NULL DEFAULT false,
    review_id VARCHAR(128) NOT NULL DEFAULT '',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(user_id, idempotency_key)
);
CREATE UNIQUE INDEX review_runs_one_active_revision
    ON review_runs(revision_id) WHERE status IN ('QUEUED','RUNNING');
CREATE INDEX review_runs_claim_idx ON review_runs(status, next_attempt_at, created_at);

CREATE TABLE review_run_events (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    run_id VARCHAR(128) NOT NULL REFERENCES review_runs(id) ON DELETE CASCADE,
    event_type VARCHAR(64) NOT NULL,
    phase VARCHAR(128) NOT NULL,
    payload_json TEXT NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX review_run_events_run_idx ON review_run_events(run_id, id);

CREATE TABLE review_ratings (
    id VARCHAR(128) PRIMARY KEY,
    review_id VARCHAR(128) NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
    user_id VARCHAR(200) NOT NULL,
    pertinence INT NOT NULL CHECK (pertinence BETWEEN 1 AND 5),
    actionability INT NOT NULL CHECK (actionability BETWEEN 1 AND 5),
    educational_value INT NOT NULL CHECK (educational_value BETWEEN 1 AND 5),
    comment TEXT NOT NULL DEFAULT '',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(review_id, user_id)
);

CREATE TABLE runtime_model_profiles (
    id VARCHAR(128) PRIMARY KEY,
    name VARCHAR(200) NOT NULL UNIQUE,
    protocol VARCHAR(64) NOT NULL,
    base_url TEXT NOT NULL,
    model VARCHAR(200) NOT NULL,
    api_key_ciphertext TEXT NOT NULL DEFAULT '',
    temperature DOUBLE PRECISION NOT NULL,
    max_tokens INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT true,
    active BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX runtime_model_one_active ON runtime_model_profiles(active) WHERE active = true;

CREATE TABLE admin_audit_events (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    admin_user_id VARCHAR(200) NOT NULL,
    action VARCHAR(128) NOT NULL,
    target_type VARCHAR(64) NOT NULL,
    target_id VARCHAR(128) NOT NULL,
    result VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE content_provision_outbox (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    course_id VARCHAR(128) NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
    event_type VARCHAR(64) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
