-- Additive upgrade. V1 rows/checksums remain unchanged and cannot authorize v2 promotion.
ALTER TABLE route_revisions ADD COLUMN phase VARCHAR(32) NOT NULL DEFAULT 'DRAFT';
ALTER TABLE route_revisions ADD COLUMN shadow_percentage INTEGER NOT NULL DEFAULT 0 CHECK (shadow_percentage BETWEEN 0 AND 100);
ALTER TABLE route_revisions ADD COLUMN checksum_version INTEGER NOT NULL DEFAULT 1 CHECK (checksum_version IN (1,2));

-- v0.1 has one global serving route. Fail migration if historical active owners conflict.
CREATE UNIQUE INDEX one_active_rollout ON rollouts ((true))
  WHERE state NOT IN ('ROLLED_BACK', 'BLOCKED');

CREATE TABLE gateway_ack_history (
  gateway_instance_id VARCHAR(128) NOT NULL,
  route_id UUID NOT NULL REFERENCES route_revisions(route_id),
  route_version BIGINT NOT NULL,
  checksum CHAR(64) NOT NULL,
  applied_at TIMESTAMPTZ NOT NULL,
  recorded_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (gateway_instance_id, route_id, route_version, checksum)
);

CREATE TABLE gateway_window_reports (
  gateway_instance_id VARCHAR(128) NOT NULL,
  boot_id UUID NOT NULL,
  route_id UUID NOT NULL REFERENCES route_revisions(route_id),
  window_start TIMESTAMPTZ NOT NULL,
  window_end TIMESTAMPTZ NOT NULL CHECK (window_end > window_start),
  canonical_hash CHAR(64) NOT NULL,
  payload JSONB NOT NULL,
  received_at TIMESTAMPTZ NOT NULL,
  PRIMARY KEY (gateway_instance_id, boot_id, route_id, window_start)
);

CREATE TABLE evidence_windows_v2 (
  window_id UUID PRIMARY KEY,
  window_version BIGINT NOT NULL CHECK (window_version = 1),
  rollout_id UUID NOT NULL REFERENCES rollouts(id),
  route_id UUID NOT NULL REFERENCES route_revisions(route_id),
  window_start TIMESTAMPTZ NOT NULL,
  window_end TIMESTAMPTZ NOT NULL CHECK (window_end > window_start),
  canonical_hash CHAR(64) NOT NULL,
  payload JSONB NOT NULL,
  received_at TIMESTAMPTZ NOT NULL,
  UNIQUE (route_id, window_start)
);

CREATE TABLE policy_evaluations_v2 (
  window_id UUID PRIMARY KEY REFERENCES evidence_windows_v2(window_id),
  policy_version INTEGER NOT NULL,
  status VARCHAR(32) NOT NULL,
  eligible BOOLEAN NOT NULL,
  breached BOOLEAN NOT NULL,
  consecutive_breaches INTEGER NOT NULL,
  coverage DOUBLE PRECISION NOT NULL,
  created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE evidence_window_results (
  window_id UUID PRIMARY KEY REFERENCES evidence_windows_v2(window_id),
  result JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE promotion_evidence_uses (
  window_id UUID PRIMARY KEY REFERENCES evidence_windows_v2(window_id),
  rollout_id UUID NOT NULL REFERENCES rollouts(id),
  from_route_id UUID NOT NULL REFERENCES route_revisions(route_id),
  candidate_ratio INTEGER NOT NULL CHECK (candidate_ratio IN (1,10,50,100)),
  actor VARCHAR(200) NOT NULL,
  used_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE rollback_sources (
  source_id UUID PRIMARY KEY,
  rollout_id UUID NOT NULL REFERENCES rollouts(id),
  kind VARCHAR(16) NOT NULL CHECK (kind IN ('MANUAL','POLICY')),
  actor VARCHAR(200) NOT NULL,
  reason TEXT NOT NULL,
  payload JSONB NOT NULL,
  created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE rollback_route_targets (
  decision_id UUID PRIMARY KEY REFERENCES rollout_decisions(decision_id),
  source_id UUID NOT NULL REFERENCES rollback_sources(source_id),
  route_id UUID NOT NULL REFERENCES route_revisions(route_id) DEFERRABLE INITIALLY DEFERRED,
  route_version BIGINT NOT NULL,
  checksum CHAR(64) NOT NULL
);

CREATE UNIQUE INDEX convergence_once ON gateway_convergence_evidence(rollout_id, target_route_version);

CREATE FUNCTION reject_evidence_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER route_revisions_append_only BEFORE UPDATE OR DELETE ON route_revisions FOR EACH ROW EXECUTE FUNCTION reject_evidence_mutation();
CREATE TRIGGER ack_history_append_only BEFORE UPDATE OR DELETE ON gateway_ack_history FOR EACH ROW EXECUTE FUNCTION reject_evidence_mutation();
CREATE TRIGGER gateway_windows_append_only BEFORE UPDATE OR DELETE ON gateway_window_reports FOR EACH ROW EXECUTE FUNCTION reject_evidence_mutation();
CREATE TRIGGER evidence_v2_append_only BEFORE UPDATE OR DELETE ON evidence_windows_v2 FOR EACH ROW EXECUTE FUNCTION reject_evidence_mutation();
CREATE TRIGGER policy_v2_append_only BEFORE UPDATE OR DELETE ON policy_evaluations_v2 FOR EACH ROW EXECUTE FUNCTION reject_evidence_mutation();
CREATE TRIGGER window_results_append_only BEFORE UPDATE OR DELETE ON evidence_window_results FOR EACH ROW EXECUTE FUNCTION reject_evidence_mutation();
CREATE TRIGGER evidence_v1_append_only BEFORE UPDATE OR DELETE ON evidence_windows FOR EACH ROW EXECUTE FUNCTION reject_evidence_mutation();
CREATE TRIGGER policy_append_only BEFORE UPDATE OR DELETE ON policy_evaluations FOR EACH ROW EXECUTE FUNCTION reject_evidence_mutation();
CREATE TRIGGER promotion_uses_append_only BEFORE UPDATE OR DELETE ON promotion_evidence_uses FOR EACH ROW EXECUTE FUNCTION reject_evidence_mutation();
CREATE TRIGGER rollback_sources_append_only BEFORE UPDATE OR DELETE ON rollback_sources FOR EACH ROW EXECUTE FUNCTION reject_evidence_mutation();
CREATE TRIGGER rollback_routes_append_only BEFORE UPDATE OR DELETE ON rollback_route_targets FOR EACH ROW EXECUTE FUNCTION reject_evidence_mutation();
CREATE TRIGGER rollback_instances_append_only BEFORE UPDATE OR DELETE ON rollback_decision_targets FOR EACH ROW EXECUTE FUNCTION reject_evidence_mutation();
CREATE TRIGGER convergence_append_only BEFORE UPDATE OR DELETE ON gateway_convergence_evidence FOR EACH ROW EXECUTE FUNCTION reject_evidence_mutation();
CREATE TRIGGER audit_append_only BEFORE UPDATE OR DELETE ON rollout_audit_events FOR EACH ROW EXECUTE FUNCTION reject_evidence_mutation();
