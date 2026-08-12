CREATE SEQUENCE route_revision_version_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE rollouts (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    state VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL CHECK (version > 0),
    candidate_ratio INTEGER NOT NULL CHECK (candidate_ratio BETWEEN 0 AND 100),
    baseline_deployment_id VARCHAR(128) NOT NULL,
    baseline_base_url VARCHAR(1024) NOT NULL,
    candidate_deployment_id VARCHAR(128) NOT NULL,
    candidate_base_url VARCHAR(1024) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE route_revisions (
    route_id UUID PRIMARY KEY,
    rollout_id UUID NOT NULL REFERENCES rollouts(id),
    version BIGINT NOT NULL UNIQUE CHECK (version > 0),
    baseline_deployment_id VARCHAR(128) NOT NULL,
    baseline_base_url VARCHAR(1024) NOT NULL,
    candidate_deployment_id VARCHAR(128) NOT NULL,
    candidate_base_url VARCHAR(1024) NOT NULL,
    candidate_ratio INTEGER NOT NULL CHECK (candidate_ratio BETWEEN 0 AND 100),
    checksum CHAR(64) NOT NULL CHECK (checksum ~ '^[a-f0-9]{64}$'),
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE idempotency_records (
    idempotency_key VARCHAR(200) PRIMARY KEY,
    request_hash CHAR(64) NOT NULL,
    status_code INTEGER NOT NULL,
    response_body JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idempotency_expiry_idx ON idempotency_records(expires_at);

CREATE TABLE rollout_audit_events (
    id UUID PRIMARY KEY,
    rollout_id UUID NOT NULL REFERENCES rollouts(id),
    action VARCHAR(64) NOT NULL,
    actor VARCHAR(200) NOT NULL,
    reason TEXT NOT NULL,
    old_version BIGINT NOT NULL,
    new_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE evidence_windows (
    id UUID PRIMARY KEY,
    rollout_id UUID NOT NULL REFERENCES rollouts(id),
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    candidate_requests INTEGER NOT NULL CHECK (candidate_requests >= 0),
    candidate_errors INTEGER NOT NULL CHECK (candidate_errors BETWEEN 0 AND candidate_requests),
    error_rate NUMERIC(8, 7) NOT NULL,
    evidence_digest CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE policy_evaluations (
    id UUID PRIMARY KEY,
    rollout_id UUID NOT NULL REFERENCES rollouts(id),
    evidence_window_id UUID NOT NULL REFERENCES evidence_windows(id),
    policy_version INTEGER NOT NULL,
    threshold NUMERIC(8, 7) NOT NULL,
    breached BOOLEAN NOT NULL,
    consecutive_breaches INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE rollout_decisions (
    decision_id UUID PRIMARY KEY,
    rollout_id UUID NOT NULL REFERENCES rollouts(id),
    decision VARCHAR(32) NOT NULL CHECK (decision = 'ROLLBACK'),
    from_route_version BIGINT NOT NULL,
    to_route_version BIGINT NOT NULL,
    window_start TIMESTAMPTZ NOT NULL,
    window_end TIMESTAMPTZ NOT NULL,
    candidate_requests INTEGER NOT NULL,
    candidate_errors INTEGER NOT NULL,
    error_rate NUMERIC(8, 7) NOT NULL,
    threshold NUMERIC(8, 7) NOT NULL,
    policy_version INTEGER NOT NULL,
    evidence_digest CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE OR REPLACE FUNCTION reject_rollout_decision_mutation()
RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'rollout_decisions is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER rollout_decisions_no_update
BEFORE UPDATE OR DELETE ON rollout_decisions
FOR EACH ROW EXECUTE FUNCTION reject_rollout_decision_mutation();

CREATE TABLE gateway_route_acks (
    gateway_instance_id VARCHAR(128) PRIMARY KEY,
    route_id UUID NOT NULL,
    route_version BIGINT NOT NULL,
    checksum CHAR(64) NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE gateway_convergence_evidence (
    id UUID PRIMARY KEY,
    rollout_id UUID NOT NULL REFERENCES rollouts(id),
    target_route_version BIGINT NOT NULL,
    required_instances JSONB NOT NULL,
    converged_instances JSONB NOT NULL,
    converged_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE rollback_decision_targets (
    decision_id UUID NOT NULL REFERENCES rollout_decisions(decision_id),
    gateway_instance_id VARCHAR(128) NOT NULL,
    PRIMARY KEY (decision_id, gateway_instance_id)
);
